package xyz.hisname.fireflyiii.repository.budget

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import xyz.hisname.fireflyiii.data.local.dao.AppDatabase
import xyz.hisname.fireflyiii.data.remote.api.BudgetService
import xyz.hisname.fireflyiii.repository.BaseViewModel
import xyz.hisname.fireflyiii.repository.models.budget.BudgetData
import xyz.hisname.fireflyiii.repository.models.budget.budgetList.BudgetListData
import xyz.hisname.fireflyiii.repository.models.error.ErrorModel
import xyz.hisname.fireflyiii.util.DateTimeUtil
import xyz.hisname.fireflyiii.util.network.NetworkErrors
import xyz.hisname.fireflyiii.util.network.retrofitCallback

class BudgetViewModel(application: Application): BaseViewModel(application) {

    val repository: BudgetRepository
    private val budgetService by lazy { genericService()?.create(BudgetService::class.java) }
    private var currentMonthBudgetLimit = 0.toDouble()
    private var currentMonthSpent = 0.toDouble()
    private var currentMonthBudgetValue: MutableLiveData<String> = MutableLiveData()
    private var currentMonthSpentValue: MutableLiveData<String> = MutableLiveData()
    val spentBudgetLoader: MutableLiveData<Boolean> = MutableLiveData()
    val currentMonthBudgetLoader: MutableLiveData<Boolean> = MutableLiveData()
    val budgetName =  MutableLiveData<String>()

    init {
        val budgetDao = AppDatabase.getInstance(application).budgetDataDao()
        val budgetListDao = AppDatabase.getInstance(application).budgetListDataDao()
        repository = BudgetRepository(budgetDao, budgetListDao)
    }

    fun retrieveAllBudgetLimits(): LiveData<MutableList<BudgetListData>> {
        isLoading.value = true
        var budgetListData: MutableList<BudgetListData> = arrayListOf()
        val data: MutableLiveData<MutableList<BudgetListData>> = MutableLiveData()
        budgetService?.getPaginatedSpentBudget(1)?.enqueue(retrofitCallback({ response ->
            val responseBody = response.body()
            if(responseBody != null) {
                val networkData = responseBody.data
                scope.launch(Dispatchers.IO) {
                    repository.deleteBudgetList()
                }.invokeOnCompletion {
                    budgetListData.addAll(networkData)
                    if (responseBody.meta.pagination.total_pages > responseBody.meta.pagination.current_page){
                        for(items in 2..responseBody.meta.pagination.total_pages){
                            budgetService?.getPaginatedSpentBudget(items)?.enqueue(retrofitCallback({ pagination ->
                                pagination.body()?.data?.forEachIndexed{ _, pigData ->
                                    budgetListData.add(pigData)
                                }
                            }))
                        }
                    }
                    budgetListData.forEachIndexed{ _, budgetData ->
                        scope.launch(Dispatchers.IO) {
                            repository.insertBudgetList(budgetData)
                        }
                    }
                    data.postValue(budgetListData.toMutableList())
                }
            } else {
                val responseError = response.errorBody()
                if (responseError != null) {
                    val errorBody = String(responseError.bytes())
                    val gson = Gson().fromJson(errorBody, ErrorModel::class.java)
                    apiResponse.postValue(gson.message)
                }
                scope.async(Dispatchers.IO) {
                    budgetListData = repository.allBudgetList()
                }.invokeOnCompletion {
                    data.postValue(budgetListData)
                }
            }
            isLoading.value = false
        })
        { throwable ->
            scope.async(Dispatchers.IO) {
                budgetListData = repository.allBudgetList()
            }.invokeOnCompletion {
                data.postValue(budgetListData)
            }
            isLoading.value = false
            apiResponse.postValue(NetworkErrors.getThrowableMessage(throwable.localizedMessage))
        })
        return data
    }

    fun getBudgetByName(budgetName: String): LiveData<MutableList<BudgetListData>>{
        var budgetListData: MutableList<BudgetListData> = arrayListOf()
        val data: MutableLiveData<MutableList<BudgetListData>> = MutableLiveData()
        scope.async(Dispatchers.IO) {
            budgetListData = repository.searchBudgetByName("%$budgetName%")
        }.invokeOnCompletion {
            data.postValue(budgetListData)
        }
        return data
    }

    fun postBudgetName(details: String?){
        budgetName.value = details
    }

    fun retrieveCurrentMonthBudget(currencyCode: String): LiveData<String>{
        currentMonthBudgetLoader.value = true
        var availableBudget: MutableList<BudgetData>? = null
        loadRemoteLimit()
        currentMonthBudgetLimit = 0.toDouble()
        scope.async(Dispatchers.IO){
            availableBudget = repository.retrieveConstraintBudgetWithCurrency(DateTimeUtil.getStartOfMonth(),
                    DateTimeUtil.getEndOfMonth(), currencyCode)
        }.invokeOnCompletion {
            if(availableBudget.isNullOrEmpty()){
                currentMonthBudgetLimit = 0.toDouble()
                currentMonthBudgetLoader.postValue(false)
            } else {
                availableBudget?.forEachIndexed { _, budgetData ->
                   currentMonthBudgetLimit += budgetData.budgetAttributes?.amount?.toDouble() ?: 0.toDouble()
                }
            }
            currentMonthBudgetValue.postValue(currentMonthBudgetLimit.toString())
            currentMonthBudgetLoader.postValue(false)
        }
        return currentMonthBudgetValue
    }

    fun retrieveSpentBudget(): LiveData<String>{
        spentBudgetLoader.value = true
        currentMonthSpent = 0.toDouble()
        var budgetListData: MutableList<BudgetListData> = arrayListOf()
        budgetService?.getPaginatedSpentBudget(1, DateTimeUtil.getStartOfMonth(),
                DateTimeUtil.getEndOfMonth())?.enqueue(retrofitCallback({ response ->
            if (response.isSuccessful) {
                val responseBody = response.body()
                if (responseBody != null) {
                    scope.launch(Dispatchers.IO) {
                        repository.deleteBudgetList()
                    }.invokeOnCompletion {
                        val networkData = responseBody.data
                        scope.launch(Dispatchers.Main) {
                            networkData.forEachIndexed { _, budgetData ->
                                budgetListData.add(budgetData)
                            }
                        }.invokeOnCompletion {
                            if (responseBody.meta.pagination.total_pages >= responseBody.meta.pagination.current_page) {
                                for (pagination in 2..responseBody.meta.pagination.total_pages) {
                                    budgetService?.getPaginatedSpentBudget(pagination, DateTimeUtil.getStartOfMonth(),
                                            DateTimeUtil.getEndOfMonth())?.enqueue(retrofitCallback({ respond ->
                                        respond.body()?.data?.forEachIndexed { _, budgetList ->
                                            budgetListData.add(budgetList)
                                        }
                                    }))
                                }
                            }
                        }
                        scope.launch(Dispatchers.IO) {
                            budgetListData.forEachIndexed { _, data -> repository.insertBudgetList(data) }
                        }.invokeOnCompletion {
                            scope.launch(Dispatchers.IO) {
                                budgetListData = repository.allBudgetList()
                            }.invokeOnCompletion {
                                currentMonthSpent = 0.toDouble()
                                budgetListData.forEachIndexed { _, budgetData ->
                                    budgetData.budgetListAttributes?.spent?.forEachIndexed { _, spent ->
                                        currentMonthSpent += spent.amount
                                    }
                                }
                                spentBudgetLoader.postValue(false)
                                currentMonthSpentValue.postValue(Math.abs(currentMonthSpent).toString())
                            }
                        }
                    }
                } else {
                    val responseError = response.errorBody()
                    if (responseError != null) {
                        val errorBody = String(responseError.bytes())
                        val gson = Gson().fromJson(errorBody, ErrorModel::class.java)
                        apiResponse.postValue(gson.message)
                    }
                    scope.launch(Dispatchers.IO) {
                        budgetListData = repository.allBudgetList()
                    }.invokeOnCompletion {
                        currentMonthSpent = 0.toDouble()
                        budgetListData.forEachIndexed { _, budgetData ->
                            budgetData.budgetListAttributes?.spent?.forEachIndexed { _, spent ->
                                currentMonthSpent += spent.amount
                            }
                        }
                        spentBudgetLoader.postValue(false)
                        currentMonthSpentValue.postValue(Math.abs(currentMonthSpent).toString())
                    }
                }
            }
        })
        { throwable ->
            scope.launch(Dispatchers.IO) {
                budgetListData = repository.allBudgetList()
            }.invokeOnCompletion {
                currentMonthSpent = 0.toDouble()
                budgetListData.forEachIndexed { _, budgetData ->
                    budgetData.budgetListAttributes?.spent?.forEachIndexed { _, spent ->
                        currentMonthSpent += spent.amount
                    }
                }
                currentMonthSpentValue.postValue(Math.abs(currentMonthSpent).toString())
                spentBudgetLoader.postValue(false)
            }
            apiResponse.postValue(NetworkErrors.getThrowableMessage(throwable.localizedMessage))
        })
        return currentMonthSpentValue
    }

    private fun loadRemoteLimit(){
        isLoading.value = true
        budgetService?.getAllBudget()?.enqueue(retrofitCallback({ response ->
            if (response.isSuccessful) {
                val networkData = response.body()
                if (networkData != null) {
                    if(networkData.meta.pagination.current_page == networkData.meta.pagination.total_pages){
                        networkData.budgetData.forEachIndexed { _, budgetData ->
                            scope.launch(Dispatchers.IO) { repository.insertBudget(budgetData) }
                        }
                    } else {
                        networkData.budgetData.forEachIndexed { _, budgetData ->
                            scope.launch(Dispatchers.IO) { repository.insertBudget(budgetData) }
                        }
                        for (pagination in 2..networkData.meta.pagination.total_pages) {
                            budgetService?.getPaginatedBudget(pagination)?.enqueue(retrofitCallback({ respond ->
                                respond.body()?.budgetData?.forEachIndexed { _, budgetList ->
                                    scope.launch(Dispatchers.IO) { repository.insertBudget(budgetList) }
                                }
                            }))
                        }
                    }
                }

            } else {
                val responseError = response.errorBody()
                if (responseError != null) {
                    val errorBody = String(responseError.bytes())
                    val gson = Gson().fromJson(errorBody, ErrorModel::class.java)
                    apiResponse.postValue(gson.message)
                }
            }
        })
        { throwable -> apiResponse.postValue(NetworkErrors.getThrowableMessage(throwable.localizedMessage)) })
        isLoading.value = false

    }
}