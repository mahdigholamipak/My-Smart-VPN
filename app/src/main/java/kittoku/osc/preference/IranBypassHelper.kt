package kittoku.osc.preference

import android.content.SharedPreferences
import android.util.Log

/**
 * Helper class for Iran Bypass functionality
 * Contains predefined list of Iranian app package names for split tunneling
 */
object IranBypassHelper {
    private const val TAG = "IranBypassHelper"
    
    /**
     * Predefined list of Iranian app package names to exclude from VPN
     * Includes: Banks, Taxis, Messengers, Navigation, and popular Iranian apps
     */
    val IRANIAN_PACKAGES = setOf(
        // Banks - بانک‌ها
        "com.bmi.api",                      // بانک صادرات
        "com.mellat.mobile",                // بانک ملت
        "ir.bmi.mobile.app",                // همراه بانک صادرات
        "com.pasargad.mobile.mobilebank",   // موبایل بانک پاسارگاد
        "com.parsian.mobilebank",           // موبایل بانک پارسیان
        "ir.samanbankir.mobilebank",        // موبایل بانک سامان
        "ir.tejaratbank.mobilebank",        // موبایل بانک تجارت
        "com.tosanafc.mobilebanksepah",     // بانک سپه
        "ir.eghtesadnovin.mobilebank",      // بانک اقتصاد نوین
        "ir.mb.bank.irbank",                // بانک ملی
        "ir.mosalla.blubank",               // بلو بانک
        "com.karatbank.android",            // کارات با زرین‌پال
        "com.ayan.mobile.bank",             // آیان بانک
        "ir.bankrefah.mobile",              // بانک رفاه
        "ir.bps.mobilebank",                // بانک سپه
        "ir.bk.mobile.bank",                // بانک کشاورزی
        "ir.resalat.refah",                 // بانک رسالت
        "com.sib.mobile.bank",              // بانک سینا
        
        // Taxis & Ride-sharing - تاکسی‌ها
        "cab.snapp.passenger",              // اسنپ
        "com.tap30.passenger",              // تپسی
        "ir.maxim.app",                     // ماکسیم
        "ir.carpinoapp.passenger",          // کارپینو
        
        // Messengers - پیام‌رسان‌ها
        "ir.eitaa.messenger",               // ایتا
        "ir.resaneh.soroush",               // سروش
        "com.iGap.messenger",               // آی‌گپ
        "org.rubika.messenger",             // روبیکا
        "ir.gap.messenger",                 // گپ
        "ir.bale.messenger",                // بله
        
        // Navigation & Maps - نقشه و مسیریاب
        "ir.neshan.navigator",              // نشان
        "ir.balad",                         // بلد
        "com.waze",                         // ویز (اگرچه خارجی اما در ایران مشکل دارد)
        
        // Shopping & E-commerce - فروشگاه‌ها
        "com.digikala",                     // دیجی‌کالا
        "ir.basalam.app",                   // باسلام
        "com.torob.app",                    // ترب
        "com.takhfifan.app",                // تخفیفان
        "com.snappmarket.buyer",            // اسنپ‌مارکت
        
        // Food Delivery - سفارش غذا
        "ir.snappfood",                     // اسنپ‌فود
        "ir.changecom.delivery",            // چیلیوری
        "com.changhe.main",                 // ریحون
        
        // Government & Utilities - دولتی
        "ir.shahr.gov.android",             // شهروند
        "ir.epolice.epolice",               // پلیس من
        "ir.dolat.app",                     // دولت همراه
        "ir.my.iran.app",                   // ایران من
        "ir.baskhabar.tg",                  // بسکخبر
        
        // Entertainment - سرگرمی
        "ir.filimo.app",                    // فیلیمو
        "ir.aparat.app",                    // آپارات
        "com.namava.app",                   // نماوا
        "ir.telewebion",                    // تلوبیون
        
        // Music - موسیقی
        "com.melovir.android",              // ملودیفا
        "ir.radiojavan",                    // رادیوجوان
        
        // Other popular apps - سایر
        "ir.cafebazaar",                    // کافه‌بازار
        "ir.myket",                         // مایکت
        "ir.divar.app",                     // دیوار
        "ir.sheypoor.app",                  // شیپور
        "com.alibaba.app",                  // علی‌بابا
        "ir.safarmarket.app",               // سفرمارکت
        "com.snapp.box.customer"            // اسنپ‌باکس
    )
    
    /**
     * Apply Iran Bypass settings
     * When enabled: Set app-based rule ON and add Iranian packages to excluded apps
     * When disabled: Remove Iranian packages (but keep user-added ones)
     */
    fun applyIranBypass(prefs: SharedPreferences, enabled: Boolean) {
        Log.d(TAG, "Applying Iran Bypass: enabled=$enabled")
        
        val editor = prefs.edit()
        
        if (enabled) {
            // Enable app-based routing
            editor.putBoolean("ROUTE_DO_ENABLE_APP_BASED_RULE", true)
            
            // Get current excluded apps and add Iranian ones
            val currentApps = prefs.getStringSet("ROUTE_ALLOWED_APPS", emptySet()) ?: emptySet()
            val newApps = currentApps.toMutableSet()
            newApps.addAll(IRANIAN_PACKAGES)
            
            editor.putStringSet("ROUTE_ALLOWED_APPS", newApps)
            Log.d(TAG, "Added ${IRANIAN_PACKAGES.size} Iranian packages to exclusion list")
        } else {
            // Remove Iranian packages but keep user-added ones
            val currentApps = prefs.getStringSet("ROUTE_ALLOWED_APPS", emptySet()) ?: emptySet()
            val newApps = currentApps.toMutableSet()
            newApps.removeAll(IRANIAN_PACKAGES)
            
            editor.putStringSet("ROUTE_ALLOWED_APPS", newApps)
            Log.d(TAG, "Removed Iranian packages from exclusion list")
        }
        
        editor.apply()
    }
    
    /**
     * Check if Iran Bypass is currently enabled
     */
    fun isEnabled(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean("IRAN_BYPASS_ENABLED", false)
    }
}
