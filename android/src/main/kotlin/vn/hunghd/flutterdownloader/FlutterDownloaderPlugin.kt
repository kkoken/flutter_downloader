package vn.hunghd.flutterdownloader

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class FlutterDownloaderPlugin : MethodChannel.MethodCallHandler, FlutterPlugin {
    private var flutterChannel: MethodChannel? = null
    private var taskDao: TaskDao? = null
    private var context: Context? = null
    private var callbackHandle: Long = 0
    private var debugMode = 0
    private val initializationLock = Any()

    private fun onAttachedToEngine(applicationContext: Context, messenger: BinaryMessenger?) {
        synchronized(initializationLock) {
            if (flutterChannel != null) {
                return
            }
            context = applicationContext
            flutterChannel = MethodChannel(messenger, CHANNEL)
            flutterChannel!!.setMethodCallHandler(this)
            val dbHelper = TaskDbHelper.getInstance(context!!)
            taskDao = TaskDao(dbHelper!!)
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when {
            call.method.equals("initialize") -> {
                initialize(call, result)
            }
            call.method.equals("registerCallback") -> {
                registerCallback(call, result)
            }
            call.method.equals("enqueue") -> {
                enqueue(call, result)
            }
            call.method.equals("loadTasks") -> {
                loadTasks(call, result)
            }
            call.method.equals("loadTasksWithRawQuery") -> {
                loadTasksWithRawQuery(call, result)
            }
            call.method.equals("cancel") -> {
                cancel(call, result)
            }
            call.method.equals("cancelAll") -> {
                cancelAll(call, result)
            }
            call.method.equals("pause") -> {
                pause(call, result)
            }
            call.method.equals("resume") -> {
                resume(call, result)
            }
            call.method.equals("retry") -> {
                retry(call, result)
            }
            call.method.equals("open") -> {
                open(call, result)
            }
            call.method.equals("remove") -> {
                remove(call, result)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = null
        if (flutterChannel != null) {
            flutterChannel!!.setMethodCallHandler(null)
            flutterChannel = null
        }
    }

    private fun buildRequest(
        url: String,
        savedDir: String,
        filename: String,
        headers: String,
        showNotification: Boolean,
        openFileFromNotification: Boolean,
        isResume: Boolean,
        requiresStorageNotLow: Boolean,
        saveInPublicStorage: Boolean
    ): WorkRequest {
        return OneTimeWorkRequest.Builder(DownloadWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(requiresStorageNotLow)
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(DownloadWorker.ARG_URL, url)
                    .putString(DownloadWorker.ARG_SAVED_DIR, savedDir)
                    .putString(DownloadWorker.ARG_FILE_NAME, filename)
                    .putString(DownloadWorker.ARG_HEADERS, headers)
                    .putBoolean(DownloadWorker.ARG_SHOW_NOTIFICATION, showNotification)
                    .putBoolean(
                        DownloadWorker.ARG_OPEN_FILE_FROM_NOTIFICATION,
                        openFileFromNotification
                    )
                    .putBoolean(DownloadWorker.ARG_IS_RESUME, isResume)
                    .putLong(DownloadWorker.ARG_CALLBACK_HANDLE, callbackHandle)
                    .putBoolean(DownloadWorker.ARG_DEBUG, debugMode == 1)
                    .putBoolean(DownloadWorker.ARG_SAVE_IN_PUBLIC_STORAGE, saveInPublicStorage)
                    .build()
            )
            .build()
    }

    private fun sendUpdateProgress(id: String, status: Int, progress: Int) {
        val args: MutableMap<String, Any> = HashMap()
        args["task_id"] = id
        args["status"] = status
        args["progress"] = progress
        flutterChannel?.invokeMethod("updateProgress", args)
    }

    private fun initialize(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as List<*>
        val callbackHandle = args[0].toString().toLong()
        debugMode = args[1].toString().toInt()
        val pref = context!!.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        pref.edit().putLong(CALLBACK_DISPATCHER_HANDLE_KEY, callbackHandle).apply()
        result.success(null)
    }

    private fun registerCallback(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as List<*>
        callbackHandle = args[0].toString().toLong()
        result.success(null)
    }

    private fun enqueue(call: MethodCall, result: MethodChannel.Result) {
        val url: String? = call.argument("url")
        val savedDir: String? = call.argument("saved_dir")
        val filename: String? = call.argument("file_name")
        val headers: String? = call.argument("headers")
        val showNotification: Boolean = call.argument("show_notification") ?: false
        val openFileFromNotification: Boolean =
            call.argument("open_file_from_notification") ?: false
        val requiresStorageNotLow: Boolean = call.argument("requires_storage_not_low") ?: false
        val saveInPublicStorage: Boolean = call.argument("save_in_public_storage") ?: false
        val request: WorkRequest? = url?.let {
            savedDir?.let { it1 ->
                filename?.let { it2 ->
                    headers?.let { it3 ->
                        buildRequest(
                            it,
                            it1,
                            it2,
                            it3,
                            showNotification,
                            openFileFromNotification,
                            false,
                            requiresStorageNotLow,
                            saveInPublicStorage
                        )
                    }
                }
            }
        }
        if (request != null) context?.let { WorkManager.getInstance(it).enqueue(request) }

        val taskId: String = request?.id.toString()
        result.success(taskId)
        sendUpdateProgress(taskId, DownloadStatus.ENQUEUED, 0)
        taskDao!!.insertOrUpdateNewTask(
            taskId, url, DownloadStatus.ENQUEUED, 0, filename,
            savedDir, headers, showNotification, openFileFromNotification, saveInPublicStorage
        )
    }

    private fun loadTasks(call: MethodCall, result: MethodChannel.Result) {
        val tasks = taskDao!!.loadAllTasks()
        val array: MutableList<Map<*, *>> = ArrayList()
        for (task in tasks) {
            val item: MutableMap<String, Any> = HashMap()
            item["task_id"] = task.taskId
            item["status"] = task.status
            item["progress"] = task.progress
            item["url"] = task.url
            item["file_name"] = task.filename
            item["saved_dir"] = task.savedDir
            item["time_created"] = task.timeCreated
            array.add(item)
        }
        result.success(array)
    }

    private fun loadTasksWithRawQuery(call: MethodCall, result: MethodChannel.Result) {
        val query: String? = call.argument("query")
        val tasks = taskDao!!.loadTasksWithRawQuery(query)
        val array: MutableList<Map<*, *>> = ArrayList()
        for (task in tasks) {
            val item: MutableMap<String, Any> = HashMap()
            item["task_id"] = task.taskId
            item["status"] = task.status
            item["progress"] = task.progress
            item["url"] = task.url
            item["file_name"] = task.filename
            item["saved_dir"] = task.savedDir
            item["time_created"] = task.timeCreated
            array.add(item)
        }
        result.success(array)
    }

    private fun cancel(call: MethodCall, result: MethodChannel.Result) {
        val taskId: String? = call.argument("task_id")
        context?.let { WorkManager.getInstance(it).cancelWorkById(UUID.fromString(taskId)) }
        result.success(null)
    }

    private fun cancelAll(call: MethodCall, result: MethodChannel.Result) {
        context?.let { WorkManager.getInstance(it).cancelAllWorkByTag(TAG) }
        result.success(null)
    }

    private fun pause(call: MethodCall, result: MethodChannel.Result) {
        val taskId: String? = call.argument("task_id")
        // mark the current task is cancelled to process pause request
        // the worker will depends on this flag to prepare data for resume request
        taskId?.let { taskDao!!.updateTask(it, true) }
        // cancel running task, this method causes WorkManager.isStopped() turning true and the download loop will be stopped
        context?.let { WorkManager.getInstance(it).cancelWorkById(UUID.fromString(taskId)) }
        result.success(null)
    }

    private fun resume(call: MethodCall, result: MethodChannel.Result) {
        val taskId: String? = call.argument("task_id")
        val task = taskId?.let { taskDao!!.loadTask(it) }
        val requiresStorageNotLow: Boolean = call.argument("requires_storage_not_low") ?: false
        if (task != null) {
            if (task.status == DownloadStatus.PAUSED) {
                val filename = task.filename
                val partialFilePath = task.savedDir + File.separator + filename
                val partialFile = File(partialFilePath)
                if (partialFile.exists()) {
                    val request: WorkRequest = buildRequest(
                        task.url, task.savedDir, task.filename,
                        task.headers, task.showNotification, task.openFileFromNotification,
                        true, requiresStorageNotLow, task.saveInPublicStorage
                    )
                    val newTaskId: String = request.id.toString()
                    result.success(newTaskId)
                    sendUpdateProgress(newTaskId, DownloadStatus.RUNNING, task.progress)
                    taskDao!!.updateTask(
                        taskId,
                        newTaskId,
                        DownloadStatus.RUNNING,
                        task.progress,
                        false
                    )
                    context?.let { WorkManager.getInstance(it).enqueue(request) }
                } else {
                    taskDao!!.updateTask(taskId, false)
                    result.error(
                        "invalid_data",
                        "not found partial downloaded data, this task cannot be resumed",
                        null
                    )
                }
            } else {
                result.error("invalid_status", "only paused task can be resumed", null)
            }
        } else {
            result.error("invalid_task_id", "not found task corresponding to given task id", null)
        }
    }

    private fun retry(call: MethodCall, result: MethodChannel.Result) {
        val taskId: String? = call.argument("task_id")
        val task = taskId?.let { taskDao!!.loadTask(it) }
        val requiresStorageNotLow: Boolean = call.argument("requires_storage_not_low") ?: false
        if (task != null) {
            if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELED) {
                val request: WorkRequest = buildRequest(
                    task.url, task.savedDir, task.filename,
                    task.headers, task.showNotification, task.openFileFromNotification,
                    false, requiresStorageNotLow, task.saveInPublicStorage
                )
                val newTaskId: String = request.id.toString()
                result.success(newTaskId)
                sendUpdateProgress(newTaskId, DownloadStatus.ENQUEUED, task.progress)
                taskDao!!.updateTask(
                    taskId,
                    newTaskId,
                    DownloadStatus.ENQUEUED,
                    task.progress,
                    false
                )
                context?.let { WorkManager.getInstance(it).enqueue(request) }
            } else {
                result.error("invalid_status", "only failed and canceled task can be retried", null)
            }
        } else {
            result.error("invalid_task_id", "not found task corresponding to given task id", null)
        }
    }

    private fun open(call: MethodCall, result: MethodChannel.Result) {
        val taskId: String? = call.argument("task_id")
        val task = taskId?.let { taskDao!!.loadTask(it) }
        if (task != null) {
            if (task.status == DownloadStatus.COMPLETE) {
                val fileURL = task.url
                val savedDir = task.savedDir
                val filename = task.filename
                val saveFilePath = savedDir + File.separator + filename
                val intent = IntentUtils.validatedFileIntent(context!!, saveFilePath, task.mimeType)
                if (intent != null) {
                    context!!.startActivity(intent)
                    result.success(true)
                } else {
                    result.success(false)
                }
            } else {
                result.error("invalid_status", "only success task can be opened", null)
            }
        } else {
            result.error("invalid_task_id", "not found task corresponding to given task id", null)
        }
    }

    private fun remove(call: MethodCall, result: MethodChannel.Result) {
        val taskId: String? = call.argument("task_id")
        val shouldDeleteContent: Boolean = call.argument("should_delete_content") ?: false
        val task = taskId?.let { taskDao!!.loadTask(it) }
        if (task != null) {
            if (task.status == DownloadStatus.ENQUEUED || task.status == DownloadStatus.RUNNING) {
                context?.let { WorkManager.getInstance(it).cancelWorkById(UUID.fromString(taskId)) }
            }
            if (shouldDeleteContent) {
                val filename = task.filename
                val saveFilePath = task.savedDir + File.separator + filename
                val tempFile = File(saveFilePath)
                if (tempFile.exists()) {
                    deleteFileInMediaStore(tempFile)
                    tempFile.delete()
                }
            }
            taskDao!!.deleteTask(taskId)
            NotificationManagerCompat.from(context!!).cancel(task.primaryId)
            result.success(null)
        } else {
            result.error("invalid_task_id", "not found task corresponding to given task id", null)
        }
    }

    private fun deleteFileInMediaStore(file: File) {
        // Set up the projection (we only need the ID)
        val projection = arrayOf(MediaStore.Images.Media._ID)

        // Match on the file path
        val imageSelection = MediaStore.Images.Media.DATA + " = ?"
        val videoSelection = MediaStore.Video.Media.DATA + " = ?"
        val selectionArgs = arrayOf(file.absolutePath)

        // Query for the ID of the media matching the file path
        val imageQueryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videoQueryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val contentResolver = context!!.contentResolver

        // search the file in image store first
        val imageCursor =
            contentResolver.query(imageQueryUri, projection, imageSelection, selectionArgs, null)
        if (imageCursor != null && imageCursor.moveToFirst()) {
            // We found the ID. Deleting the item via the content provider will also remove the file
            val id =
                imageCursor.getLong(imageCursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            val deleteUri =
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            contentResolver.delete(deleteUri, null, null)
        } else {
            // File not found in image store DB, try to search in video store
            val videoCursor = contentResolver.query(
                imageQueryUri,
                projection,
                imageSelection,
                selectionArgs,
                null
            )
            if (videoCursor != null && videoCursor.moveToFirst()) {
                // We found the ID. Deleting the item via the content provider will also remove the file
                val id =
                    videoCursor.getLong(videoCursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val deleteUri =
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                contentResolver.delete(deleteUri, null, null)
            } else {
                // can not find the file in media store DB at all
            }
            videoCursor?.close()
        }
        imageCursor?.close()
    }

    companion object {
        private const val CHANNEL = "vn.hunghd/downloader"
        private const val TAG = "flutter_download_task"
        const val SHARED_PREFERENCES_KEY = "vn.hunghd.downloader.pref"
        const val CALLBACK_DISPATCHER_HANDLE_KEY = "callback_dispatcher_handle_key"
    }
}
