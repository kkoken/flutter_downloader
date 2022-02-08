package vn.hunghd.flutterdownloader

class DownloadTask internal constructor(
    var primaryId: Int,
    var taskId: String,
    var status: Int,
    var progress: Int,
    var url: String,
    var filename: String,
    var savedDir: String,
    var headers: String,
    var mimeType: String,
    var resumable: Boolean,
    var showNotification: Boolean,
    var openFileFromNotification: Boolean,
    var timeCreated: Long,
    var saveInPublicStorage: Boolean
) {
    override fun toString(): String {
        return "DownloadTask{taskId=$taskId,status=$status,progress=$progress,url=$url,filename=$filename,savedDir=$savedDir,headers=$headers, saveInPublicStorage= $saveInPublicStorage}"
    }
}