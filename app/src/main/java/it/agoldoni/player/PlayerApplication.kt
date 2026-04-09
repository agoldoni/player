package it.agoldoni.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import it.agoldoni.player.domain.OrphanCleanupUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PlayerApplication : Application() {

    @Inject lateinit var orphanCleanup: OrphanCleanupUseCase

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { orphanCleanup() }
    }
}
