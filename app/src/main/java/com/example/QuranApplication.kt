package com.example

import android.app.Application
import com.example.data.SupabaseClientProvider
import com.example.data.repository.RepositoryProvider

class QuranApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RepositoryProvider.init(this)
        SupabaseClientProvider.init(this)
    }
}
