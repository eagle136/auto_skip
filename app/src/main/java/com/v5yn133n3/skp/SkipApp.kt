package com.v5yn133n3.skp

import android.app.Application
import com.v5yn133n3.skp.data.RuleStore

class SkipApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RuleStore.init(this)
        Logs.init(this)
    }
}
