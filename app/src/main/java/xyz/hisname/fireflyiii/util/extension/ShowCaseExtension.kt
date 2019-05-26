package xyz.hisname.fireflyiii.util.extension

import android.app.Activity
import android.preference.PreferenceManager
import android.view.View
import android.view.animation.AnimationUtils
import androidx.annotation.StringRes
import me.toptas.fancyshowcase.FancyShowCaseView
import me.toptas.fancyshowcase.FocusShape
import xyz.hisname.fireflyiii.R
import xyz.hisname.fireflyiii.data.local.pref.AppPref
import androidx.fragment.app.Fragment as SupportFragment


fun SupportFragment.showCase(@StringRes title: Int, showOnce: String, layout: View) =
        requireActivity().showCase(title, showOnce, layout)

fun Activity.showCase(@StringRes title: Int, showOnce: String, layout: View): FancyShowCaseView{
    val enterAnimation = AnimationUtils.loadAnimation(this, R.anim.slide_from_left)
    val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
    val showCaseView = FancyShowCaseView.Builder(this)
            .focusOn(layout)
            .title(resources.getString(title))
            .enableAutoTextPosition()
            .showOnce(showOnce)
            .fitSystemWindows(true)
            .focusShape(FocusShape.ROUNDED_RECTANGLE)
            .enterAnimation(enterAnimation)
            .closeOnTouch(true)
    if(AppPref(sharedPref).nightModeEnabled){
        showCaseView.focusBorderColor(R.color.md_green_400)
    }
    if(!showCaseView.build().isShownBefore()){
        layout.focusOnView()
    }
    return showCaseView.build()
}