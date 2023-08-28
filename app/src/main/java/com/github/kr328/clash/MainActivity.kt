package com.github.kr328.clash

import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.store.TipsStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.util.concurrent.TimeUnit
import com.blankj.utilcode.util.*
import androidx.core.content.FileProvider.getUriForFile
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.service.ClashService

val CFG = """mixed-port: 7890
allow-lan: true
mode: rule
log-level: info
external-controller: '0.0.0.0:9090'
secret: ''
cfw-bypass:
  - localhost
  - 127.*
  - 10.*
  - 192.168.*
  - <local>
cfw-latency-timeout: 5000
proxies:
  - { name: 测试2, server: 192.168.1.1, port: 1080, type: http }
proxy-groups:
  - name: auto
    type: url-test
    proxies:
      - DIRECT
    url: 'https://hpd.baidu.com/v.gif'
    interval: 300
rules:
  - 'MATCH,DIRECT'""";
class MainActivity : BaseActivity<MainDesign>() {
    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)


        launch(Dispatchers.IO) {
            showUpdatedTips(design)
        }

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs ->
                            startActivity(LogsActivity::class.intent)
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setClashConfig(CFG);
    }


    private fun getSaveFolder(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val folder = File(this.filesDir,packageInfo.versionName)
        FileUtils.createOrExistsDir(folder.path)
        return folder.path
    }
    private fun getSavePath(name: String): String {
        val folder = getSaveFolder()
        val file = File(folder,name)
        return file.path
    }

    private fun saveFile(data:String,name: String):File {
        val link = getSavePath(name)
        val file = File(link)
        try {
            val writer = FileWriter(file)
            writer.append(data)
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.d("H5AppLogs","保存配置文件失败")
            e.printStackTrace()
        }

        return file
    }
    private fun setClashConfig(data:String) {
        val name = "default.yaml"

        val file = saveFile(data,name)

//        var ef = File("/sdcard/0/", name)
//        try {
//            val writer = FileWriter(ef)
//            writer.append(data)
//            writer.flush()
//            writer.close()
//        } catch (e: Exception) {
//            Log.d("H5AppLogs","保存配置文件失败")
//            e.printStackTrace()
//        }
        val authority: String = packageName + ".appFileProvider"
        val  uri = getUriForFile(this,authority,file)


        launch {
            try {
                withProfile {
                    var profile = queryActive()
                    if (profile == null){
                        var id= create(Profile.Type.Url,"default", uri.toString())
                        profile = queryByUUID(id)
                    }

                    if (profile != null) {
                        patch(profile.uuid, profile.name, uri.toString(), 0)
                        commit(profile.uuid)
                        setActive(profile)
                    }
                    Log.d("H5AppLogs","setClashConfig finish")
                }

            } catch (e: Exception) {
                Log.e("H5AppLogs","writeConfig fail")
                e.printStackTrace()
            } finally {
                Log.d("H5AppLogs","isUpdateConfig finish")

//                startForegroundServiceCompat(ClashService::class.intent)

                design!!.startClash();
            }
        }
    }

    private suspend fun showUpdatedTips(design: MainDesign) {
        val tips = TipsStore(this)

        if (tips.primaryVersion != TipsStore.CURRENT_PRIMARY_VERSION) {
            tips.primaryVersion = TipsStore.CURRENT_PRIMARY_VERSION

            val pkg = packageManager.getPackageInfo(packageName, 0)

            if (pkg.firstInstallTime != pkg.lastUpdateTime) {
                design.showUpdatedTips()
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(R.string.no_profile_selected, ToastDuration.Long) {
                setAction(R.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(R.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName
        }
    }
}