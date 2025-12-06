package kittoku.osc.preference.custom

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.preference.Preference
import kittoku.osc.activity.LogActivity
import kittoku.osc.preference.OscPrefKey


internal class HomeLogPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs), OscPreference {
    override val oscPrefKey = OscPrefKey.HOME_LOG
    override val parentKey: OscPrefKey? = OscPrefKey.HOME_CATEGORY_LOG
    override val preferenceTitle = ""

    override fun updateView() {}

    override fun onAttached() {
        setOnPreferenceClickListener {
            Intent(context, LogActivity::class.java).also {
                context.startActivity(it)
            }

            true
        }
    }
}
