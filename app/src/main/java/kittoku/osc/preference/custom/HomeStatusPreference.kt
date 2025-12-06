package kittoku.osc.preference.custom

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import kittoku.osc.preference.OscPrefKey

internal class HomeStatusPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs), OscPreference {
    override val oscPrefKey = OscPrefKey.HOME_STATUS
    override val parentKey: OscPrefKey = OscPrefKey.HOME_CATEGORY_STATUS
    override val preferenceTitle = ""

    override fun updateView() {}
}