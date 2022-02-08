package vn.hunghd.flutterdownloader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.collections.ArrayDeque
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params),
    MethodChannel.MethodCallHandler {
    private val charsetPattern = Pattern.compile("(?i)\\bcharset=\\s*\"?([^\\s;\"]*)")
    private val filenameStarPattern =
        Pattern.compile("(?i)\\bfilename\\*=([^']+)'([^']*)'\"?([^\"]+)\"?")
    private val filenamePattern = Pattern.compile("(?i)\\bfilename=\"?([^\"]+)\"?")
    private var backgroundChannel: MethodChannel? = null
    private var dbHelper: TaskDbHelper? = null
    private var taskDao: TaskDao? = null
    private var showNotification = false
    private var clickToOpenDownloadedFile = false
    private var debug = false
    private var lastProgress = 0
    private var primaryId = 0
    private var msgStarted: String? = null
    private var msgInProgress: String? = null
    private var msgCanceled: String? = null
    private var msgFailed: String? = null
    private var msgPaused: String? = null
    private var msgComplete: String? = null
    private var lastCallUpdateNotification: Long = 0
    private var saveInPublicStorage = false
    private fun startBackgroundIsolate(context: Context) {
        synchronized(isolateStarted) {
            if (backgroundFlutterEngine == null) {
                val pref = context.getSharedPreferences(
                    FlutterDownloaderPlugin.SHARED_PREFERENCES_KEY,
                    Context.MODE_PRIVATE
                )
                val callbackHandle =
                    pref.getLong(FlutterDownloaderPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, 0)
                backgroundFlutterEngine =
                    FlutterEngine(applicationContext)

                // We need to create an instance of `FlutterEngine` before looking up the
                // callback. If we don't, the callback cache won't be initialized and the
                // lookup will fail.
                val flutterCallback: FlutterCallbackInformation =
                    FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)

                val appBundlePath: String =
                    FlutterInjector.instance().flutterLoader().findAppBundlePath()
                val assets: AssetManager = applicationContext.assets
                backgroundFlutterEngine!!.dartExecutor
                    .executeDartCallback(
                        DartExecutor.DartCallback(
                            assets,
                            appBundlePath,
                            flutterCallback
                        )
                    )
            }
        }
        backgroundChannel = MethodChannel(
            backgroundFlutterEngine!!.dartExecutor,
            "vn.hunghd/downloader_background"
        )
        backgroundChannel!!.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method.equals("didInitializeDispatcher")) {
            synchronized(isolateStarted) {
                while (!isolateQueue.isEmpty()) {
                    backgroundChannel?.invokeMethod(
                        "",
                        isolateQueue.removeFirst()
                    )
                }
                isolateStarted.set(true)
                result.success(null)
            }
        } else {
            result.notImplemented()
        }
    }

    override fun onStopped() {
        val context: Context = applicationContext
        dbHelper = TaskDbHelper.getInstance(context)
        taskDao = dbHelper?.let { TaskDao(it) }
        val filename: String? = inputData.getString(ARG_FILE_NAME)
        val task: DownloadTask? = taskDao?.loadTask(id.toString())
        if (task?.status == DownloadStatus.ENQUEUED) {
            updateNotification(context, filename, DownloadStatus.CANCELED, -1, null, true)
            taskDao?.updateTask(id.toString(), DownloadStatus.CANCELED, lastProgress)
        }
    }

    override fun doWork(): Result {
        val context: Context = applicationContext
        dbHelper = TaskDbHelper.getInstance(context)
        taskDao = dbHelper?.let { TaskDao(it) }
        val url: String? = inputData.getString(ARG_URL)
        val filename: String? = inputData.getString(ARG_FILE_NAME)
        val savedDir: String? = inputData.getString(ARG_SAVED_DIR)
        val headers: String? = inputData.getString(ARG_HEADERS)
        var isResume: Boolean = inputData.getBoolean(ARG_IS_RESUME, false)
        debug = inputData.getBoolean(ARG_DEBUG, false)
        val res: Resources = applicationContext.resources
        msgStarted = res.getString(R.string.flutter_downloader_notification_started)
        msgInProgress = res.getString(R.string.flutter_downloader_notification_in_progress)
        msgCanceled = res.getString(R.string.flutter_downloader_notification_canceled)
        msgFailed = res.getString(R.string.flutter_downloader_notification_failed)
        msgPaused = res.getString(R.string.flutter_downloader_notification_paused)
        msgComplete = res.getString(R.string.flutter_downloader_notification_complete)
        val task: DownloadTask? = taskDao?.loadTask(id.toString())
        log("DownloadWorker{url=" + url + ",filename=" + filename + ",savedDir=" + savedDir + ",header=" + headers + ",isResume=" + isResume + ",status=" + if (task != null) task.status else "GONE")

        // Task has been deleted or cancelled
        if (task == null || task.status == DownloadStatus.CANCELED) {
            return Result.success()
        }
        showNotification = inputData.getBoolean(ARG_SHOW_NOTIFICATION, false)
        clickToOpenDownloadedFile =
            inputData.getBoolean(ARG_OPEN_FILE_FROM_NOTIFICATION, false)
        saveInPublicStorage = inputData.getBoolean(ARG_SAVE_IN_PUBLIC_STORAGE, false)
        primaryId = task.primaryId
        setupNotification(context)
        updateNotification(
            context,
            filename, DownloadStatus.RUNNING, task.progress, null, false
        )
        taskDao?.updateTask(id.toString(), DownloadStatus.RUNNING, task.progress)

        //automatic resume for partial files. (if the workmanager unexpectedly quited in background)
        val saveFilePath = savedDir + File.separator + filename
        val partialFile = File(saveFilePath)
        if (partialFile.exists()) {
            isResume = true
            log("exists file for " + filename + "automatic resuming...")
        }
        return try {
            if (url != null) {
                if (savedDir != null) {
                    if (headers != null) {
                        downloadFile(context, url, savedDir, filename, headers, isResume)
                    }
                }
            }
            cleanUp()
            dbHelper = null
            taskDao = null
            Result.success()
        } catch (e: Exception) {
            updateNotification(context, filename, DownloadStatus.FAILED, -1, null, true)
            taskDao?.updateTask(id.toString(), DownloadStatus.FAILED, lastProgress)
            e.printStackTrace()
            dbHelper = null
            taskDao = null
            Result.failure()
        }
    }

    private fun setupHeaders(conn: HttpURLConnection?, headers: String) {
        if (!TextUtils.isEmpty(headers)) {
            log("Headers = $headers")
            try {
                val json = JSONObject(headers)
                val it = json.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    conn!!.setRequestProperty(key, json.getString(key))
                }
                conn!!.doInput = true
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
    }

    private fun setupPartialDownloadedDataHeader(
        conn: HttpURLConnection?,
        filename: String?,
        savedDir: String
    ): Long {
        val saveFilePath = savedDir + File.separator + filename
        val partialFile = File(saveFilePath)
        val downloadedBytes = partialFile.length()
        log("Resume download: Range: bytes=$downloadedBytes-")
        conn!!.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Range", "bytes=$downloadedBytes-")
        conn.doInput = true
        return downloadedBytes
    }

    @Throws(IOException::class)
    private fun downloadFile(
        context: Context,
        fileURL: String,
        savedDir: String,
        _filename: String?,
        headers: String,
        isResume: Boolean
    ) {
        var filename = _filename
        var url = fileURL
        var resourceUrl: URL
        var base: URL
        var next: URL
        val visited: MutableMap<String, Int>
        var httpConn: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var location: String
        var downloadedBytes: Long = 0
        var responseCode: Int
        var times: Int
        visited = HashMap()
        try {
            // handle redirection logic
            while (true) {
                if (!visited.containsKey(url)) {
                    times = 1
                    visited[url] = times
                } else {
                    times = visited[url]!! + 1
                }
                if (times > 3) throw IOException("Stuck in redirect loop")
                resourceUrl = URL(url)
                log("Open connection to $url")
                httpConn = resourceUrl.openConnection() as HttpURLConnection
                httpConn.connectTimeout = 15000
                httpConn.readTimeout = 15000
                httpConn.instanceFollowRedirects =
                    false // Make the logic below easier to detect redirections
                httpConn.setRequestProperty("User-Agent", "Mozilla/5.0...")

                // setup request headers if it is set
                setupHeaders(httpConn, headers)
                // try to continue downloading a file from its partial downloaded data.
                if (isResume) {
                    downloadedBytes = setupPartialDownloadedDataHeader(httpConn, filename, savedDir)
                }
                responseCode = httpConn.responseCode
                when (responseCode) {
                    HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_SEE_OTHER, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308 -> {
                        log("Response with redirection code")
                        location = httpConn.getHeaderField("Location")
                        log("Location = $location")
                        base = URL(url)
                        next = URL(base, location) // Deal with relative URLs
                        url = next.toExternalForm()
                        log("New url: $url")
                        continue
                    }
                }
                break
            }
            httpConn!!.connect()
            val contentType: String
            if ((responseCode == HttpURLConnection.HTTP_OK || isResume && responseCode == HttpURLConnection.HTTP_PARTIAL) && !isStopped) {
                contentType = httpConn.contentType
                val contentLength =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        httpConn.contentLengthLong
                    } else {
                        httpConn.contentLength
                            .toLong()
                    }
                log("Content-Type = $contentType")
                log("Content-Length = $contentLength")
                val charset = getCharsetFromContentType(contentType)
                log("Charset = $charset")
                if (!isResume) {
                    // try to extract filename from HTTP headers if it is not given by user
                    if (filename == null) {
                        val disposition = httpConn.getHeaderField("Content-Disposition")
                        log("Content-Disposition = $disposition")
                        if (disposition != null && !disposition.isEmpty()) {
                            filename = getFileNameFromContentDisposition(disposition, charset)
                        }
                        if (filename == null || filename.isEmpty()) {
                            filename = url.substring(url.lastIndexOf("/") + 1)
                            try {
                                filename = URLDecoder.decode(filename, "UTF-8")
                            } catch (e: IllegalArgumentException) {
                                /* ok, just let filename be not encoded */
                                e.printStackTrace()
                            }
                        }
                    }
                }
                log("fileName = $filename")
                taskDao?.updateTask(id.toString(), filename, contentType)

                // opens input stream from the HTTP connection
                inputStream = httpConn.inputStream
                val savedFilePath: String?
                // opens an output stream to save into file
                // there are two case:
                if (isResume) {
                    // 1. continue downloading (append data to partial downloaded file)
                    savedFilePath = savedDir + File.separator + filename
                    outputStream = FileOutputStream(savedFilePath, true)
                } else {
                    // 2. new download, create new file
                    // there are two case according to Android SDK version and save path
                    // From Android 11 onwards, file is only downloaded to app-specific directory (internal storage)
                    // or public shared download directory (external storage).
                    // The second option will ignore `savedDir` parameter.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && saveInPublicStorage) {
                        val uri = createFileInPublicDownloadsDir(filename, contentType)
                        savedFilePath = getMediaStoreEntryPathApi29(uri)
                        outputStream = context.contentResolver.openOutputStream(uri!!, "w")
                    } else {
                        val file = createFileInAppSpecificDir(filename, savedDir)
                        savedFilePath = file!!.path
                        outputStream = FileOutputStream(file, false)
                    }
                }
                var count = downloadedBytes
                var bytesRead = -1
                val buffer = ByteArray(BUFFER_SIZE)
                // using isStopped() to monitor canceling task
                while (inputStream.read(buffer).also { bytesRead = it } != -1 && !isStopped) {
                    count += bytesRead.toLong()
                    val progress = (count * 100 / (contentLength + downloadedBytes)).toInt()
                    outputStream!!.write(buffer, 0, bytesRead)
                    if ((lastProgress == 0 || progress > lastProgress + STEP_UPDATE || progress == 100)
                        && progress != lastProgress
                    ) {
                        lastProgress = progress

                        // This line possibly causes system overloaded because of accessing to DB too many ?!!!
                        // but commenting this line causes tasks loaded from DB missing current downloading progress,
                        // however, this missing data should be temporary and it will be updated as soon as
                        // a new bunch of data fetched and a notification sent
                        taskDao?.updateTask(id.toString(), DownloadStatus.RUNNING, progress)
                        updateNotification(
                            context,
                            filename,
                            DownloadStatus.RUNNING,
                            progress,
                            null,
                            false
                        )
                    }
                }
                val task: DownloadTask? = taskDao?.loadTask(id.toString())
                val progress = if (isStopped && task?.resumable == true) lastProgress else 100
                val status: Int =
                    if (isStopped) if (task?.resumable == true) DownloadStatus.PAUSED else DownloadStatus.CANCELED else DownloadStatus.COMPLETE
                val storage = ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                var pendingIntent: PendingIntent? = null
                if (status == DownloadStatus.COMPLETE) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        if (isImageOrVideoFile(contentType) && isExternalStoragePath(savedFilePath)) {
                            addImageOrVideoToGallery(
                                filename,
                                savedFilePath,
                                getContentTypeWithoutCharset(contentType)
                            )
                        }
                    }
                    if (clickToOpenDownloadedFile) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && storage != PackageManager.PERMISSION_GRANTED) return
                        val intent: Intent? = IntentUtils.validatedFileIntent(
                            applicationContext,
                            savedFilePath,
                            contentType
                        )
                        if (intent != null) {
                            log("Setting an intent to open the file $savedFilePath")
                            val flags =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_CANCEL_CURRENT
                            pendingIntent =
                                PendingIntent.getActivity(applicationContext, 0, intent, flags)
                        } else {
                            log("There's no application that can open the file $savedFilePath")
                        }
                    }
                }
                taskDao?.updateTask(id.toString(), status, progress)
                updateNotification(context, filename, status, progress, pendingIntent, true)
                log(if (isStopped) "Download canceled" else "File downloaded")
            } else {
                val task: DownloadTask? = taskDao?.loadTask(id.toString())
                val status: Int =
                    if (isStopped) if (task?.resumable == true) DownloadStatus.PAUSED else DownloadStatus.CANCELED else DownloadStatus.FAILED
                taskDao?.updateTask(id.toString(), status, lastProgress)
                updateNotification(context, filename ?: fileURL, status, -1, null, true)
                log(if (isStopped) "Download canceled" else "Server replied HTTP code: $responseCode")
            }
        } catch (e: IOException) {
            taskDao?.updateTask(id.toString(), DownloadStatus.FAILED, lastProgress)
            updateNotification(context, filename ?: fileURL, DownloadStatus.FAILED, -1, null, true)
            e.printStackTrace()
        } finally {
            if (outputStream != null) {
                outputStream.flush()
                try {
                    outputStream.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            httpConn?.disconnect()
        }
    }

    /**
     * Create a file using java.io API
     */
    private fun createFileInAppSpecificDir(filename: String?, savedDir: String): File? {
        val newFile = File(savedDir, filename)
        try {
            val rs = newFile.createNewFile()
            if (rs) {
                return newFile
            } else {
                logError("It looks like you are trying to save file in public storage but not setting 'saveInPublicStorage' to 'true'")
            }
        } catch (e: IOException) {
            e.printStackTrace()
            logError("Create a file using java.io API failed ")
        }
        return null
    }

    /**
     * Create a file inside the Download folder using MediaStore API
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createFileInPublicDownloadsDir(filename: String?, mimeType: String): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues()
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename)
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType)
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        val contentResolver: ContentResolver = applicationContext.contentResolver
        try {
            return contentResolver.insert(collection, values)
        } catch (e: Exception) {
            e.printStackTrace()
            logError("Create a file using MediaStore API failed.")
        }
        return null
    }

    /**
     * Get a path for a MediaStore entry as it's needed when calling MediaScanner
     */
    private fun getMediaStoreEntryPathApi29(uri: Uri?): String? {
        return try {
            uri?.let {
                applicationContext.contentResolver.query(
                    it, arrayOf(MediaStore.Files.FileColumns.DATA),
                    null,
                    null,
                    null
                ).use { cursor ->
                    if (cursor == null) return null
                    return if (!cursor.moveToFirst()) null else cursor.getString(
                        cursor.getColumnIndexOrThrow(
                            MediaStore.Files.FileColumns.DATA
                        )
                    )
                }
            }
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            logError("Get a path for a MediaStore failed")
            return null
        }
    }

    fun scanFilePath(path: String, mimeType: String, callback: CallbackUri) {
        MediaScannerConnection.scanFile(
            applicationContext, arrayOf(path), arrayOf(mimeType)
        ) { path1: String?, uri: Uri? ->
            callback.invoke(
                uri
            )
        }
    }

    private fun cleanUp() {
        val task: DownloadTask? = taskDao?.loadTask(id.toString())
        if (task != null && task.status != DownloadStatus.COMPLETE && !task.resumable) {
            val filename: String = task.filename

            // check and delete uncompleted file
            val saveFilePath: String = task.savedDir + File.separator + filename
            val tempFile = File(saveFilePath)
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private val notificationIconRes: Int
        get() {
            try {
                val applicationInfo: ApplicationInfo = applicationContext.packageManager
                    .getApplicationInfo(
                        applicationContext.packageName,
                        PackageManager.GET_META_DATA
                    )
                val appIconResId = applicationInfo.icon
                return applicationInfo.metaData.getInt(
                    "vn.hunghd.flutterdownloader.NOTIFICATION_ICON",
                    appIconResId
                )
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }
            return 0
        }

    private fun setupNotification(context: Context) {
        if (!showNotification) return
        // Make a channel if necessary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel, but only on API 26+ because
            // the NotificationChannel class is new and not in the support library
            val res: Resources = applicationContext.resources
            val channelName = res.getString(R.string.flutter_downloader_notification_channel_name)
            val channelDescription =
                res.getString(R.string.flutter_downloader_notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance)
            channel.description = channelDescription
            channel.setSound(null, null)

            // Add the channel
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(
        context: Context,
        title: String?,
        status: Int,
        progress: Int,
        intent: PendingIntent?,
        finalize: Boolean
    ) {
        sendUpdateProcessEvent(status, progress)

        // Show the notification
        if (showNotification) {
            // Create the notification
            val builder = NotificationCompat.Builder(context, CHANNEL_ID).setContentTitle(title)
                .setContentIntent(intent)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            if (status == DownloadStatus.RUNNING) {
                when {
                    progress <= 0 -> {
                        builder.setContentText(msgStarted)
                            .setProgress(0, 0, false)
                        builder.setOngoing(false)
                            .setSmallIcon(notificationIconRes)
                    }
                    progress < 100 -> {
                        builder.setContentText(msgInProgress)
                            .setProgress(100, progress, false)
                        //builder.setOngoing(true)
                        //    .setSmallIcon(R.drawable.stat_sys_download)
                    }
                    else -> {
                        builder.setContentText(msgComplete).setProgress(0, 0, false)
                        //builder.setOngoing(false)
                        //    .setSmallIcon(R.drawable.stat_sys_download_done)
                    }
                }
            } else if (status == DownloadStatus.CANCELED) {
                builder.setContentText(msgCanceled).setProgress(0, 0, false)
                //builder.setOngoing(false)
                //    .setSmallIcon(R.drawable.stat_sys_download_done)
            } else if (status == DownloadStatus.FAILED) {
                builder.setContentText(msgFailed).setProgress(0, 0, false)
                //builder.setOngoing(false)
                //    .setSmallIcon(R.drawable.stat_sys_download_done)
            } else if (status == DownloadStatus.PAUSED) {
                builder.setContentText(msgPaused).setProgress(0, 0, false)
                //builder.setOngoing(false)
                //    .setSmallIcon(R.drawable.stat_sys_download_done)
            } else if (status == DownloadStatus.COMPLETE) {
                builder.setContentText(msgComplete).setProgress(0, 0, false)
                //builder.setOngoing(false)
                //    .setSmallIcon(R.drawable.stat_sys_download_done)
            } else {
                builder.setProgress(0, 0, false)
                builder.setOngoing(false).setSmallIcon(notificationIconRes)
            }

            // Note: Android applies a rate limit when updating a notification.
            // If you post updates to a notification too frequently (many in less than one second),
            // the system might drop some updates. (https://developer.android.com/training/notify-user/build-notification#Updating)
            //
            // If this is progress update, it's not much important if it is dropped because there're still incoming updates later
            // If this is the final update, it must be success otherwise the notification will be stuck at the processing state
            // In order to ensure the final one is success, we check and sleep a second if need.
            if (System.currentTimeMillis() - lastCallUpdateNotification < 1000) {
                if (finalize) {
                    log("Update too frequently!!!!, but it is the final update, we should sleep a second to ensure the update call can be processed")
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                } else {
                    log("Update too frequently!!!!, this should be dropped")
                    return
                }
            }
            log("Update notification: {notificationId: $primaryId, title: $title, status: $status, progress: $progress}")
            NotificationManagerCompat.from(context).notify(primaryId, builder.build())
            lastCallUpdateNotification = System.currentTimeMillis()
        }
    }

    private fun sendUpdateProcessEvent(status: Int, progress: Int) {
        val args: MutableList<Any> = ArrayList()
        val callbackHandle: Long = inputData.getLong(ARG_CALLBACK_HANDLE, 0)
        args.add(callbackHandle)
        args.add(id.toString())
        args.add(status)
        args.add(progress)
        synchronized(isolateStarted) {
            if (!isolateStarted.get()) {
                isolateQueue.add(args)
            } else {
                Handler(applicationContext.mainLooper)
                    .post(Runnable { backgroundChannel?.invokeMethod("", args) })
            }
        }
    }

    private fun getCharsetFromContentType(contentType: String?): String? {
        if (contentType == null) return null
        val m = charsetPattern.matcher(contentType)
        return if (m.find()) {
            // TODO: unsafe to use it
            m.group(1).trim { it <= ' ' }.uppercase(Locale.getDefault())
        } else null
    }

    @Throws(UnsupportedEncodingException::class)
    private fun getFileNameFromContentDisposition(
        disposition: String?,
        contentCharset: String?
    ): String? {
        if (disposition == null) return null
        var name: String? = null
        var charset = contentCharset

        //first, match plain filename, and then replace it with star filename, to follow the spec
        val plainMatcher = filenamePattern.matcher(disposition)
        if (plainMatcher.find()) name = plainMatcher.group(1)
        val starMatcher = filenameStarPattern.matcher(disposition)
        if (starMatcher.find()) {
            name = starMatcher.group(3)
            // TODO: unsafe to use it
            charset = starMatcher.group(1).uppercase(Locale.getDefault())
        }
        return if (name == null) null else URLDecoder.decode(name, charset ?: "ISO-8859-1")
    }

    private fun getContentTypeWithoutCharset(contentType: String?): String? {
        return contentType?.split(";")?.toTypedArray()?.get(0)?.trim { it <= ' ' }
    }

    private fun isImageOrVideoFile(_contentType: String): Boolean {
        var contentType: String? = _contentType
        contentType = getContentTypeWithoutCharset(contentType)
        return contentType != null && (contentType.startsWith("image/") || contentType.startsWith("video"))
    }

    private fun isExternalStoragePath(filePath: String?): Boolean {
        val externalStorageDir = Environment.getExternalStorageDirectory()
        return filePath != null && externalStorageDir != null && filePath.startsWith(
            externalStorageDir.path
        )
    }

    private fun addImageOrVideoToGallery(
        fileName: String?,
        filePath: String?,
        contentType: String?
    ) {
        if (contentType != null && filePath != null && fileName != null) {
            if (contentType.startsWith("image/")) {
                val values = ContentValues()
                values.put(MediaStore.Images.Media.TITLE, fileName)
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                values.put(MediaStore.Images.Media.DESCRIPTION, "")
                values.put(MediaStore.Images.Media.MIME_TYPE, contentType)
                values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis())
                values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                values.put(MediaStore.Images.Media.DATA, filePath)
                log("insert $values to MediaStore")
                val contentResolver: ContentResolver = applicationContext.contentResolver
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } else if (contentType.startsWith("video")) {
                val values = ContentValues()
                values.put(MediaStore.Video.Media.TITLE, fileName)
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                values.put(MediaStore.Video.Media.DESCRIPTION, "")
                values.put(MediaStore.Video.Media.MIME_TYPE, contentType)
                values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis())
                values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                values.put(MediaStore.Video.Media.DATA, filePath)
                log("insert $values to MediaStore")
                val contentResolver: ContentResolver = applicationContext.contentResolver
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }
        }
    }

    private fun log(message: String) {
        if (debug) {
            Log.d(TAG, message)
        }
    }

    private fun logError(message: String) {
        if (debug) {
            Log.e(TAG, message)
        }
    }

    interface CallbackUri {
        operator fun invoke(uri: Uri?)
    }

    companion object {
        const val ARG_URL = "url"
        const val ARG_FILE_NAME = "file_name"
        const val ARG_SAVED_DIR = "saved_file"
        const val ARG_HEADERS = "headers"
        const val ARG_IS_RESUME = "is_resume"
        const val ARG_SHOW_NOTIFICATION = "show_notification"
        const val ARG_OPEN_FILE_FROM_NOTIFICATION = "open_file_from_notification"
        const val ARG_CALLBACK_HANDLE = "callback_handle"
        const val ARG_DEBUG = "debug"
        const val ARG_SAVE_IN_PUBLIC_STORAGE = "save_in_public_storage"
        private val TAG = DownloadWorker::class.java.simpleName
        private const val BUFFER_SIZE = 4096
        private const val CHANNEL_ID = "FLUTTER_DOWNLOADER_NOTIFICATION"
        private const val STEP_UPDATE = 5
        private val isolateStarted = AtomicBoolean(false)
        private val isolateQueue = ArrayDeque<List<*>>()
        private var backgroundFlutterEngine: FlutterEngine? = null
    }

    init {
        Handler(context.mainLooper).post { startBackgroundIsolate(context) }
    }
}