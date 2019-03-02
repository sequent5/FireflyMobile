package xyz.hisname.fireflyiii.repository.transaction

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import xyz.hisname.fireflyiii.data.local.dao.AppDatabase
import xyz.hisname.fireflyiii.data.remote.api.TransactionService
import xyz.hisname.fireflyiii.repository.BaseViewModel
import xyz.hisname.fireflyiii.repository.models.ApiResponses
import xyz.hisname.fireflyiii.repository.models.error.ErrorModel
import xyz.hisname.fireflyiii.repository.models.transaction.TransactionData
import xyz.hisname.fireflyiii.repository.models.transaction.TransactionSuccessModel
import xyz.hisname.fireflyiii.util.network.NetworkErrors
import xyz.hisname.fireflyiii.util.network.retrofitCallback
import java.math.BigDecimal

class TransactionsViewModel(application: Application): BaseViewModel(application) {

    val repository: TransactionRepository
    private val transactionService by lazy { genericService()?.create(TransactionService::class.java) }

    init {
        val transactionDataDao = AppDatabase.getInstance(application).transactionDataDao()
        repository = TransactionRepository(transactionDataDao)
    }

    fun getAllData(startDate: String?, endDate: String?): LiveData<MutableList<TransactionData>>  {
        loadRemoteData(startDate, endDate, "all")
        return repository.allTransaction
    }

    fun getWithdrawalList(startDate: String?, endDate: String?) = loadRemoteData(startDate, endDate, "withdrawal")

    fun getDepositList(startDate: String?, endDate: String?) = loadRemoteData(startDate, endDate, "deposit")

    fun getTransferList(startDate: String?, endDate: String?) = loadRemoteData(startDate, endDate, "transfer")

    fun getRecentTransaction(limit: Int): LiveData<MutableList<TransactionData>>{
        isLoading.value = true
        var recentData: MutableList<TransactionData> = arrayListOf()
        val data: MutableLiveData<MutableList<TransactionData>> = MutableLiveData()
        transactionService?.getAllTransactions("","", "all")?.enqueue(retrofitCallback({ response ->
            if (response.isSuccessful) {
                val networkData = response.body()
                networkData?.data?.forEachIndexed { _, data ->
                    scope.launch(Dispatchers.IO) { repository.insertTransaction(data) }
                }
            }
        })
        { throwable -> apiResponse.postValue(NetworkErrors.getThrowableMessage(throwable.localizedMessage)) })
        scope.async(Dispatchers.IO){
            recentData = repository.recentTransactions(limit)
        }.invokeOnCompletion {
            data.postValue(recentData)
            isLoading.postValue(false)
        }
        return data
    }

    fun getWithdrawalWithCurrencyCode(startDate: String?, endDate: String?, currencyCode: String): LiveData<MutableList<TransactionData>> {
        isLoading.value = true
        var withdrawData: MutableList<TransactionData> = arrayListOf()
        val data: MutableLiveData<MutableList<TransactionData>> = MutableLiveData()
        loadRemoteData(startDate, endDate, "withdrawal")
        scope.async(Dispatchers.IO) {
            withdrawData = repository.allWithdrawalWithCurrencyCode(startDate, endDate, currencyCode)
        }.invokeOnCompletion {
            data.postValue(withdrawData)
            isLoading.postValue(false)
        }
        return data
    }

    fun getWithdrawalAmountWithCurrencyCode(startDate: String?, endDate: String?, currencyCode: String): LiveData<BigDecimal>{
        isLoading.value = true
        var withdrawData: MutableList<TransactionData> = arrayListOf()
        var withdrawAmount: BigDecimal = 0.toBigDecimal()
        val data: MutableLiveData<BigDecimal> = MutableLiveData()
        loadRemoteData(startDate, endDate, "withdrawal")
        scope.async(Dispatchers.IO) {
            withdrawData = repository.allWithdrawalWithCurrencyCode(startDate, endDate, currencyCode)
        }.invokeOnCompletion {
            withdrawData.forEachIndexed { _, transactionData ->
                withdrawAmount = withdrawAmount.add(transactionData.transactionAttributes?.amount?.toBigDecimal()?.abs())
            }
            data.postValue(withdrawAmount)
            isLoading.postValue(false)
        }
        return data
    }

    fun getDepositAmountWithCurrencyCode(startDate: String?, endDate: String?, currencyCode: String): LiveData<BigDecimal>{
        isLoading.value = true
        var depositData: MutableList<TransactionData> = arrayListOf()
        var depositAmount: BigDecimal = 0.toBigDecimal()
        val data: MutableLiveData<BigDecimal> = MutableLiveData()
        loadRemoteData(startDate, endDate, "deposit")
        scope.async(Dispatchers.IO) {
            depositData = repository.allDepositWithCurrencyCode(startDate, endDate, currencyCode)
        }.invokeOnCompletion {
            depositData.forEachIndexed { _, transactionData ->
                depositAmount = depositAmount.add(transactionData.transactionAttributes?.amount?.toBigDecimal()?.abs())
            }
            data.postValue(depositAmount)
            isLoading.postValue(false)
        }
        return data
    }

    fun getDepositWithCurrencyCode(startDate: String?, endDate: String?, currencyCode: String): LiveData<MutableList<TransactionData>> {
        isLoading.value = true
        var depositData: MutableList<TransactionData> = arrayListOf()
        val data: MutableLiveData<MutableList<TransactionData>> = MutableLiveData()
        loadRemoteData(startDate, endDate, "deposit")
        scope.async(Dispatchers.IO) {
            depositData = repository.allDepositWithCurrencyCode(startDate, endDate, currencyCode)
        }.invokeOnCompletion {
            data.postValue(depositData)
            isLoading.postValue(false)
        }
        return data
    }

    fun addTransaction(type: String, description: String,
                       date: String, piggyBankName: String?, billName: String?, amount: String,
                       sourceName: String?, destinationName: String?, currencyName: String,
                       category: String?, tags: String?, budgetName: String?): LiveData<ApiResponses<TransactionSuccessModel>>{
        val transaction: MutableLiveData<ApiResponses<TransactionSuccessModel>> = MutableLiveData()
        val apiResponse: MediatorLiveData<ApiResponses<TransactionSuccessModel>> = MediatorLiveData()
        transactionService?.addTransaction(convertString(type),description,date,piggyBankName,billName,
                amount,sourceName,destinationName,currencyName, category, tags, budgetName)?.enqueue(retrofitCallback({ response ->
            val errorBody = response.errorBody()
            var errorBodyMessage = ""
            if (errorBody != null) {
                errorBodyMessage = String(errorBody.bytes())
                val gson = Gson().fromJson(errorBodyMessage, ErrorModel::class.java)
                errorBodyMessage = when {
                    gson.errors.transactions_currency != null -> "Currency Code Required"
                    gson.errors.bill_name != null -> "Invalid Bill Name"
                    gson.errors.piggy_bank_name != null -> "Invalid Piggy Bank Name"
                    gson.errors.transactions_destination_name != null -> "Invalid Destination Account"
                    gson.errors.transactions_source_name != null -> "Invalid Source Account"
                    gson.errors.transaction_destination_id != null -> gson.errors.transaction_destination_id[0]
                    gson.errors.transaction_amount != null -> "Amount field is required"
                    gson.errors.description != null -> "Description is required"
                    else -> "Error occurred while saving transaction"
                }
            }
            if (response.isSuccessful) {
                response.body()?.data?.forEachIndexed { _, transaction ->
                    scope.launch(Dispatchers.IO) { repository.insertTransaction(transaction) }
                }
                transaction.postValue(ApiResponses(response.body()))
            } else {
                transaction.postValue(ApiResponses(errorBodyMessage))
            }
        })
        { throwable -> transaction.value = ApiResponses(throwable) })
        apiResponse.addSource(transaction) { apiResponse.value = it }
        return apiResponse
    }

    fun updateTransaction(transactionId: Long, type: String, description: String,
                       date: String, billName: String?, amount: String,
                       sourceName: String?, destinationName: String?, currencyName: String,
                       category: String?, tags: String?, budgetName: String?): LiveData<ApiResponses<TransactionSuccessModel>>{
        val transaction: MutableLiveData<ApiResponses<TransactionSuccessModel>> = MutableLiveData()
        val apiResponse: MediatorLiveData<ApiResponses<TransactionSuccessModel>> = MediatorLiveData()
        transactionService?.updateTransaction(transactionId, convertString(type),description,date,billName,
                amount,sourceName,destinationName,currencyName, category, tags, budgetName)?.enqueue(retrofitCallback({ response ->
            val errorBody = response.errorBody()
            var errorBodyMessage = ""
            if (errorBody != null) {
                errorBodyMessage = String(errorBody.bytes())
                val gson = Gson().fromJson(errorBodyMessage, ErrorModel::class.java)
                errorBodyMessage = when {
                    gson.errors.transactions_currency != null -> "Currency Code Required"
                    gson.errors.bill_name != null -> "Invalid Bill Name"
                    gson.errors.piggy_bank_name != null -> "Invalid Piggy Bank Name"
                    gson.errors.transactions_destination_name != null -> "Invalid Destination Account"
                    gson.errors.transactions_source_name != null -> "Invalid Source Account"
                    gson.errors.transaction_destination_id != null -> gson.errors.transaction_destination_id[0]
                    gson.errors.transaction_amount != null -> "Amount field is required"
                    gson.errors.description != null -> "Description is required"
                    else -> "Error occurred while saving transaction"
                }
            }
            if (response.isSuccessful) {
                response.body()?.data?.forEachIndexed { _, transaction ->
                    scope.launch(Dispatchers.IO) {
                        repository.insertTransaction(transaction)
                    }
                }
                transaction.postValue(ApiResponses(response.body()))
            } else {
                transaction.postValue(ApiResponses(errorBodyMessage))
            }
        })
        { throwable -> transaction.value = ApiResponses(throwable) })
        apiResponse.addSource(transaction) { apiResponse.value = it }
        return apiResponse
    }

    fun getTransactionById(transactionId: Long): LiveData<MutableList<TransactionData>>{
        val transactionData: MutableLiveData<MutableList<TransactionData>> = MutableLiveData()
        var data: MutableList<TransactionData> = arrayListOf()
        scope.async(Dispatchers.IO) {
            data = repository.getTransactionById(transactionId)
        }.invokeOnCompletion {
            transactionData.postValue(data)
        }
        return transactionData
    }

    fun deleteTransaction(transactionId: Long): LiveData<Boolean>{
        val isDeleted: MutableLiveData<Boolean> = MutableLiveData()
        isLoading.value = true
        transactionService?.deleteTransactionById(transactionId)?.enqueue(retrofitCallback({ response ->
            if (response.code() == 204 || response.code() == 200) {
                scope.async(Dispatchers.IO) {
                    repository.deleteTransactionById(transactionId)
                }.invokeOnCompletion {
                    isDeleted.postValue(true)
                }
            }else {
                isDeleted.postValue(false)
            }
        })
        { throwable ->
            isDeleted.postValue(false)
        })
        isLoading.value = false
        return isDeleted
    }

    private fun convertString(type: String) = type.substring(0,1).toLowerCase() + type.substring(1).toLowerCase()

    private fun loadRemoteData(startDate: String?, endDate: String?, source: String): LiveData<MutableList<TransactionData>>{
        isLoading.value = true
        var transactionData: MutableList<TransactionData> = arrayListOf()
        val data: MutableLiveData<MutableList<TransactionData>> = MutableLiveData()
        transactionService?.getPaginatedTransactions(startDate, endDate, source, 1)?.enqueue(retrofitCallback({ response ->
            if (response.isSuccessful) {
                val networkData = response.body()
                if (networkData != null) {
                    if(networkData.meta.pagination.current_page == networkData.meta.pagination.total_pages){
                        scope.launch(Dispatchers.IO){
                            repository.deleteTransactionsByDate(startDate, endDate, convertString(source))
                        }.invokeOnCompletion {
                            transactionData.addAll(networkData.data)
                            networkData.data.forEachIndexed{ _, transactionData ->
                                scope.launch(Dispatchers.IO) { repository.insertTransaction(transactionData) }
                            }
                            if(networkData.meta.pagination.total_pages > networkData.meta.pagination.current_page) {
                                for(items in 2..networkData.meta.pagination.total_pages){
                                    transactionService?.getPaginatedTransactions(startDate, endDate, source, items)?.enqueue(retrofitCallback({ pagination ->
                                        pagination.body()?.data?.forEachIndexed{ _, transData ->
                                            transactionData.add(transData)
                                        }
                                    }))
                                }
                            }
                            transactionData.forEachIndexed{ _, transData ->
                                scope.launch(Dispatchers.IO) {
                                    repository.insertTransaction(transData)
                                }
                            }
                            data.postValue(transactionData.toMutableList())
                        }
                    }
                    isLoading.value = false
                }
            } else {
                val responseError = response.errorBody()
                if (responseError != null) {
                    val errorBody = String(responseError.bytes())
                    val gson = Gson().fromJson(errorBody, ErrorModel::class.java)
                    apiResponse.postValue(gson.message)
                }
                scope.async(Dispatchers.IO) {
                    transactionData = repository.transactionList(startDate, endDate, convertString(source))
                }.invokeOnCompletion {
                    data.postValue(transactionData)
                }

                isLoading.value = false
            }
        })
        { throwable ->
            scope.async(Dispatchers.IO) {
                transactionData = repository.transactionList(startDate, endDate, convertString(source))
            }.invokeOnCompletion {

            }
            isLoading.value = false
            apiResponse.postValue(NetworkErrors.getThrowableMessage(throwable.localizedMessage))
        })
        return data
    }

}