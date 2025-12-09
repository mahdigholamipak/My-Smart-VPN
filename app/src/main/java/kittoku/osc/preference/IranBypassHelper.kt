package kittoku.osc.preference

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log

/**
 * Helper class for Iran Bypass functionality
 * 
 * ISSUE #5 FIX: Corrected inverted logic
 * 
 * Problem: VpnService uses an "AllowList" approach - only apps in the list use VPN
 * Previous Bug: Adding Iranian apps to AllowList meant ONLY those apps used VPN
 * 
 * Solution: Get ALL installed apps, subtract Iranian packages, add result to AllowList
 * This way: All apps EXCEPT Iranian ones go through VPN
 */
object IranBypassHelper {
    private const val TAG = "IranBypassHelper"
    
    /**
     * Predefined list of Iranian app package names to EXCLUDE from VPN
     * These apps will bypass VPN and use direct connection
     */
    val IRANIAN_PACKAGES = setOf(
        // --- Critical Banking & Payment ---
        "ir.melli.bam",             // بام (بانک ملی)
        "ir.wepod.app",             // ویپاد (پاسارگاد)
        "com.asanpardakht.android", // آپ (آسان پرداخت)
        "ir.tejaratbank.mobilebank",// همراه بانک تجارت
        "ir.resalat.refah",         // بانک رسالت
        "ir.mosalla.blubank",       // بلو بانک
        "com.bmi.api",              // همراه بانک ملی (قدیم)
        "ir.bsi.mobile",            // همراه بانک صادرات
        "com.mellat.mobile",        // بانک ملت
        "com.pasargad.mobile.mobilebank", // همراه بانک پاسارگاد
        "com.parsian.mobilebank",   // پارسیان
        "ir.samanbankir.mobilebank",// سامان
        "com.tosanafc.mobilebanksepah", // سپه
        "ir.eghtesadnovin.mobilebank", // اقتصاد نوین
        "ir.bankrefah.mobile",      // رفاه
        "ir.bk.mobile.bank",        // کشاورزی
        "com.sib.mobile.bank",      // سینا
        "kr.co.kbstar.global.iran", // بانک خاورمیانه
        "com.my.bank.ayandeh",      // کلید (بانک آینده)
        
        // --- Payment & Finance ---
        "com.partsoftware.sekeh",   // سکه
        "com.fcp.hizom",            // 724
        "ir.mci.ecareapp",          // همراه من
        "ir.mcn.myirancell",        // ایرانسل من
        "ir.rightel.myrightel",     // رایتل من

        // --- Taxis & Ride-sharing ---
        "cab.snapp.passenger",      // اسنپ
        "com.tap30.passenger",      // تپسی
        "ir.maxim.app",             // ماکسیم

        // --- Messengers (Internal Only) ---
        "ir.eitaa.messenger",       // ایتا
        "ir.resaneh.soroush",       // سروش
        "com.iGap.messenger",       // آی‌گپ
        "org.rubika.messenger",     // روبیکا
        "app.rbmain.a",             // روبیکا (نسخه جدید)
        "ir.gap.messenger",         // گپ
        "ir.bale.messenger",        // بله

        // --- Navigation ---
        "ir.neshan.navigator",      // نشان
        "ir.balad",                 // بلد

        // --- Shopping ---
        "com.digikala",             // دیجی‌کالا
        "ir.basalam.app",           // باسلام
        "com.torob.app",            // ترب
        "com.takhfifan.app",        // تخفیفان
        "com.snappmarket.buyer",    // اسنپ‌مارکت
        "ir.divar.app",             // دیوار
        "ir.sheypoor.app",          // شیپور

        // --- Food Delivery ---
        "ir.snappfood",             // اسنپ‌فود

        // --- Government ---
        "ir.epolice.epolice",       // پلیس من
        "ir.my.iran.app",           // دولت همراه
        "ir.sso.eservices",         // تامین اجتماعی

        // --- Entertainment (VOD) ---
        "ir.filimo.app",            // فیلیمو
        "ir.aparat.app",            // آپارات
        "com.namava.app",           // نماوا
        "ir.telewebion"             // تلوبیون
    )
    
    /**
     * ISSUE #5 FIX: Corrected Iran Bypass logic
     * 
     * INVERTED APPROACH:
     * Since VpnService uses AllowList (only listed apps use VPN),
     * we need to add ALL apps EXCEPT Iranian ones to the allowed list.
     * 
     * This way: Iranian apps bypass VPN, everything else goes through VPN.
     */
    fun applyIranBypass(context: Context, prefs: SharedPreferences, enabled: Boolean) {
        Log.d(TAG, "Applying Iran Bypass: enabled=$enabled")
        
        val editor = prefs.edit()
        
        if (enabled) {
            // Enable app-based routing
            editor.putBoolean("ROUTE_DO_ENABLE_APP_BASED_RULE", true)
            
            // ISSUE #5 FIX: Get ALL installed apps, SUBTRACT Iranian packages
            val allInstalledApps = getAllInstalledPackages(context)
            Log.d(TAG, "Found ${allInstalledApps.size} installed apps")
            
            // Remove Iranian packages from the set (these will BYPASS VPN)
            val appsToVpn = allInstalledApps.toMutableSet()
            val removed = appsToVpn.removeAll(IRANIAN_PACKAGES)
            
            Log.d(TAG, "Removed ${IRANIAN_PACKAGES.size} Iranian packages")
            Log.d(TAG, "Final: ${appsToVpn.size} apps will use VPN")
            
            editor.putStringSet("ROUTE_ALLOWED_APPS", appsToVpn)
        } else {
            // Disable: Clear the app list (all apps use VPN by default)
            editor.putBoolean("ROUTE_DO_ENABLE_APP_BASED_RULE", false)
            editor.putStringSet("ROUTE_ALLOWED_APPS", emptySet())
            Log.d(TAG, "Disabled Iran Bypass - all apps use VPN")
        }
        
        editor.apply()
    }
    
    /**
     * Legacy method for backward compatibility (without context)
     * Note: This won't work correctly for Issue #5 fix without context
     */
    fun applyIranBypass(prefs: SharedPreferences, enabled: Boolean) {
        Log.w(TAG, "Called legacy applyIranBypass without context - limited functionality")
        
        val editor = prefs.edit()
        
        if (enabled) {
            editor.putBoolean("ROUTE_DO_ENABLE_APP_BASED_RULE", true)
            // Without context, we can only add Iranian packages (incorrect behavior)
            // This is kept for backward compatibility but won't work correctly
            val currentApps = prefs.getStringSet("ROUTE_ALLOWED_APPS", emptySet()) ?: emptySet()
            val newApps = currentApps.toMutableSet()
            newApps.addAll(IRANIAN_PACKAGES)
            editor.putStringSet("ROUTE_ALLOWED_APPS", newApps)
            Log.w(TAG, "WARNING: Using legacy mode - Iran Bypass may not work correctly")
        } else {
            val currentApps = prefs.getStringSet("ROUTE_ALLOWED_APPS", emptySet()) ?: emptySet()
            val newApps = currentApps.toMutableSet()
            newApps.removeAll(IRANIAN_PACKAGES)
            editor.putStringSet("ROUTE_ALLOWED_APPS", newApps)
        }
        
        editor.apply()
    }
    
    /**
     * Get all installed package names on the device
     */
    private fun getAllInstalledPackages(context: Context): Set<String> {
        return try {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { it.packageName }
                .toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting installed packages", e)
            emptySet()
        }
    }
    
    /**
     * Check if Iran Bypass is currently enabled
     */
    fun isEnabled(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean("IRAN_BYPASS_ENABLED", false)
    }
}
