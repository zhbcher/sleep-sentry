package com.sleepsentry

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 纯本地处理，不申请网络权限，也不做任何初始化拉取。
        // 这是隐私卖点：音频不出手机。
    }
}
