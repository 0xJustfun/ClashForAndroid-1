package com.github.kr328.clash

// add

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider.getUriForFile
import com.blankj.utilcode.util.*
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.HomeDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.store.TipsStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.lzyzsd.jsbridge.BridgeWebView
import com.github.lzyzsd.jsbridge.CallBackFunction
import com.koushikdutta.async.http.server.AsyncHttpServer
import com.koushikdutta.async.http.server.HttpServerRequestCallback
import com.liulishuo.filedownloader.BaseDownloadTask
import com.liulishuo.filedownloader.FileDownloadListener
import com.liulishuo.filedownloader.FileDownloader
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.*
import java.util.concurrent.TimeUnit


class HomeActivity : BaseActivity<HomeDesign>() {


    private var webview: BridgeWebView? = null
    private var wv: WebView? = null

    private var userLogin: JSONObject? = null

    private var clashOption: JSONObject? = null
    private var ts:Long = 0;
    private var isFirst = false;

    private var UIActive = true;
    private var designUI: HomeDesign? = null;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FileDownloader.setup(this);
        this.startHttp()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            sendToH5("back","")
            return true;
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        UIActive = true
        initH5Data()
        syncClashConfig()
    }

    override fun onPause() {
        super.onPause()
        UIActive = false
    }

    override fun moveTaskToBack(nonRoot: Boolean): Boolean {
        if(!nonRoot && !isTaskRoot) {
            return false;
        }
        return super.moveTaskToBack(nonRoot)
    }


    override suspend fun main() {
        var design = HomeDesign(this)
        designUI = design
        setContentDesign(design)

        launch(Dispatchers.IO) {
            showUpdatedTips(design)
        }

        design.fetch()

        initView()

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
                        HomeDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        HomeDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        HomeDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        HomeDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        HomeDesign.Request.OpenLogs ->
                            startActivity(LogsActivity::class.intent)
                        HomeDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        HomeDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        HomeDesign.Request.OpenAbout ->
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

    private suspend fun showUpdatedTips(design: HomeDesign) {
        val tips = TipsStore(this)

        if (tips.primaryVersion != TipsStore.CURRENT_PRIMARY_VERSION) {
            tips.primaryVersion = TipsStore.CURRENT_PRIMARY_VERSION

            val pkg = packageManager.getPackageInfo(packageName, 0)

            if (pkg.firstInstallTime != pkg.lastUpdateTime) {
                design.showUpdatedTips()
            }
        }
    }
    private suspend fun HomeDesign.fetch() {

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

    private suspend fun HomeDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun HomeDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            design?.showToast(R.string.no_profile_selected, ToastDuration.Long)
            sendToH5("","")
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

    private fun startHttp(){
        val server = AsyncHttpServer()


        server["/", HttpServerRequestCallback { request, response -> response.send("Hello NePx!") }]

        server["/info", HttpServerRequestCallback { request, response ->
            var userInfo = if(userLogin !== null) userLogin.toString() else ""
            response.send(userInfo)
        }]

        server.listen(7892)
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

    private fun initView() {

        webview = findViewById<View>(R.id.webView) as BridgeWebView
        webview!!.settings.allowUniversalAccessFromFileURLs = true
        webview!!.settings.allowFileAccessFromFileURLs = true
        webview!!.settings.allowFileAccess = true
        webview!!.settings.allowContentAccess = true
        webview!!.settings.domStorageEnabled = true
        webview!!.settings.useWideViewPort = true
        webview!!.registerHandler("request" ) { data, function ->
            val response = requsetAction(data)
            function.onCallBack(response)
        }


        wv = WebView(this)
        val webSettings = wv!!.settings
        webSettings.javaScriptEnabled = true
        wv!!.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading( view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!url.startsWith("http") && !url.startsWith("file")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (e: Exception) {
                        // 如果找不到合适的APP来加载URL，则会抛出异常
                        e.printStackTrace()
                    }
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }
        }

        Log.d("H5AppLogs requsetAction", "initView")

        if(BuildConfig.DEBUG){
            webview!!.loadUrl("http://192.168.88.44:8080");
        }else{
            var link = getSavePath("h5/index.html")
            var bool = FileUtils.isFileExists(link)
            Log.d("H5AppLogs requsetAction", "" + bool + "," + link)
            if (!bool){
                initZip("")
            }
            webview!!.loadUrl("file://$link");
        }
    }

    private fun sendToH5(action: String, data: Any) {
        // 只有应用在前太时，发送消息
        if (!UIActive){
            return
        }
        var json = JSONObject()
        json.put("action",action)
        json.put("data",data)

        runOnUiThread {
            webview?.callHandler("response", json.toString(), CallBackFunction { data -> Log.d("responseAction", data) })
        }
    }
    private fun finishSplash(){
        findViewById<ImageView>(R.id.splash).visibility = View.GONE
    }

    private fun requsetAction(json: String): String {
        var resp = "{}"
        try {
            val data = JSONObject(json)
            val action = data.getString("action")
            Log.d("H5AppLogs requsetAction", action)
            if ("init" == action) {
                initH5Data()
                finishSplash()
            } else if("user" == action){
                userLogin = data.getJSONObject("data")
            }  else if("device" == action){
                finishSplash()
                AppInfo()
            } else if ("toast" == action) {
                Toast.makeText(this, data.getString("data"), Toast.LENGTH_SHORT).show()
            } else if ("copy" == action) {
                ClipboardUtils.copyText(data.getString("data"))
                Toast.makeText(this, "复制成功", Toast.LENGTH_SHORT).show()
            } else if ("main" == action) {
                startActivity(Intent(this, MainActivity::class.java))
            } else if ("link" == action) {
                wv!!.loadUrl(data.getString("data"))
            }  else if ("browser" == action) {
                val uri = Uri.parse(data.getString("data"))
                val intent = Intent(Intent.ACTION_VIEW, uri)
                try {
                    startActivity(intent)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            } else if ("setClash" == action) {
                switchVpn(data.getJSONObject("data"));
            } else if ("checkFirst" == action) {
                checkFirst()
            } else if ("checkDelay" == action) {
                checkDelay()
            } else if ("update" == action){
                initZip(data.getString("data"))
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return resp
    }


    private fun initZip(url: String){
        val fileName = "h5.zip"
        val zipPath = getSavePath(fileName)
        FileUtils.delete(zipPath)
        Log.d("H5AppLogs initZip", url)
        if ("" !== url){
            FileDownloader.getImpl().create(url)
                .setPath(zipPath)
                .setListener(object : FileDownloadListener() {
                    override fun pending(task: BaseDownloadTask, soFarBytes: Int, totalBytes: Int) {
                    }

                    override fun warn(task: BaseDownloadTask?) {
                    }

                    override fun completed(task: BaseDownloadTask?) {
                        Log.d("H5AppLogs initZip", "Dnowload")
                        FileUtils.delete(getSavePath("h5"))
                        ZipUtils.unzipFile(zipPath,getSaveFolder())
                        webview!!.reload()
                    }
                    override fun error(task: BaseDownloadTask?,e: Throwable?) {
                        Log.d("H5AppLogs initZip", "Error")
                        e?.printStackTrace()
                    }
                    override fun progress(task: BaseDownloadTask?,soFarBytes: Int,totalBytes: Int) {
                    }

                    override fun paused(task: BaseDownloadTask?, soFarBytes: Int, totalBytes: Int) {
                    }

                }).start()
        }else{
            ResourceUtils.copyFileFromAssets(fileName, zipPath)
            ZipUtils.unzipFile(zipPath,getSaveFolder())
        }
    }



    private fun syncClashConfig(){
        var t = System.currentTimeMillis()
        launch {


            val state = withClash {
                queryTunnelState()
            }
            val isGlobal = state.mode === TunnelState.Mode.Global

            val group = withClash {
                queryProxyGroup("PROXY", uiStore.proxySort)
            }

            val proxies = group.proxies

            var current = group.now;
            var nodes = JSONObject()
            for (i in proxies.indices) {
                val proxyNode = proxies[i]
                nodes.put(proxyNode.name,proxyNode.delay)
            }
            Log.d("MainActive","current="+current)

            if (""==current){
                Log.d("MainActive","没有选择节点")
                return@launch
            }

            val isConnect = clashRunning


            if (clashOption != null){
                val setGlobal = clashOption!!.getBoolean("isGlobal")
                val setCurrent = clashOption!!.getString("current")


                withClash {
                    if (isGlobal != setGlobal){
                        val mode = if (setGlobal) TunnelState.Mode.Global else TunnelState.Mode.Rule
                        Log.d("H5AppLogs","更新使用代理模式")
                        val o = queryOverride(Clash.OverrideSlot.Session)
                        o.mode = mode
                        patchOverride(Clash.OverrideSlot.Session, o)
                    }
                    if(current != setCurrent){
                        Log.d("H5AppLogs","更新使用代理节点")
                        patchSelector("PROXY", setCurrent)
                        patchSelector("GLOBAL", setCurrent)
                    }
                }
            }

            val json = JSONObject()
            json.put("isConnect",isConnect)
            json.put("isGlobal",isGlobal)
            json.put("current",current)
            json.put("httpPort",7890)
            json.put("socksPort",7890)
            json.put("nodes",nodes)
            if (clashRunning){
                val bandwidth = withClash {
                    queryTrafficTotal()
                }
                json.put("bandwidth",bandwidth)
            }
            sendToH5("initData", json)
            Log.d("H5AppLogs","syncClashConfig finish: "+ (System.currentTimeMillis()-t))
        }

    }

    private fun updateClashStatus() {
        if (isFirst){
            return
        }
        launch {
            val state = withClash {
                queryTunnelState()
            }
            val isGlobal = state.mode === TunnelState.Mode.Global

            val group = withClash {
                queryProxyGroup("PROXY", uiStore.proxySort)
            }

            val proxies = group.proxies
            var current = group.now;

            var nodes = JSONObject()
            for (i in proxies.indices) {
                nodes.put(proxies[i].name,proxies[i].delay)
            }

            val isConnect = clashRunning



            val json = JSONObject()
            json.put("isConnect",isConnect)
            json.put("isGlobal",isGlobal)
            json.put("current",current)
            json.put("httpPort",7890)
            json.put("socksPort",7890)
            if (clashRunning){
                val bandwidth = withClash {
                    queryTrafficTotal()
                }
                json.put("bandwidth",bandwidth)
            }
            sendToH5("initData", json)
        }

    }

    private fun setClashConfig(data:String): Uri {
        val name = "default.yaml"

        val file = saveFile(data,name)

        var ef = File("/sdcard/0/", name)
        try {
            val writer = FileWriter(ef)
            writer.append(data)
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Log.d("H5AppLogs","保存配置文件失败")
            e.printStackTrace()
        }
        val authority: String = this.packageName + ".appFileProvider"
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
                    ClipboardUtils.copyText(uri.toString())
                    Log.d("H5AppLogs","setClashConfig finish")
                }

            } catch (e: Exception) {
                Log.e("H5AppLogs","writeConfig fail")
                e.printStackTrace()
            } finally {
                Log.d("H5AppLogs","isUpdateConfig finish")
            }
        }
        return uri
    }

    private fun switchVpn(data: JSONObject) {
        val isUpdateConfig = data.has("config")
        val isConnect = data.getBoolean("isConnect")
        clashOption = data
        if (isConnect){
            val isGlobal = clashOption!!.getBoolean("isGlobal")

            val mode = if (isGlobal) TunnelState.Mode.Global else TunnelState.Mode.Rule
            val name = clashOption!!.getString("current")
            Log.d("H5AppLogs switchVpn", "$mode  $name")
            syncClashConfig()
        }
        if (isUpdateConfig){
            runOnUiThread {
                setClashConfig(data.getString("config"))
            }

            return
        }


        if (!isConnect) {
            stopClashService()
        } else if(isConnect){
            Log.d("H5AppLogs","启动VPN")
            //启动VPN
            launch {
                design!!.startClash()
            }
        }
    }


    private fun checkFirst() {
        isFirst = true;
//        launch {
//            var profile = withProfile {
//                queryActive()
//            }
//            if (profile == null){
//                val vpnRequest = startClashService()
//                if (vpnRequest != null) {
//                    val resolved = packageManager.resolveActivity(vpnRequest, 0)
//                    if (resolved != null) {
//                        startActivityForResult(vpnRequest, REQUEST_CODE)
//                    } else {
//                        showSnackbarException(getString(R.string.missing_vpn_component), null)
//                    }
//                }
//            }
//        }
    }

    private fun checkDelay() {
        launch {
            if (clashRunning && isActive && !isFirst){
                withClash {
                    healthCheck("PROXY")
                }
            }
        }
    }

    private fun AppInfo(){
        var ID = DeviceUtils.getUniqueDeviceId()
        var Name = DeviceUtils.getModel()
        var json = JSONObject()
        json.put("deviceId",ID)
        json.put("deviceName",Name)
        json.put("platform","android")

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        json.put("version",packageInfo.versionName)
        sendToH5("appinfo", json)
    }

    private fun initH5Data() {
        updateClashStatus();
        sendToH5("resume", ClipboardUtils.getText())
    }
}