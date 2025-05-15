package com.qe.adbrecorder

import android.annotation.SuppressLint
import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class LogcatService : Service() {

    private var isRunning = false // 로그 수집 중인지 여부를 나타냄
    private var logcatThread: Thread? = null // logcat을 실행할 스레드
    private var process: Process? = null // logcat 프로세스

    companion object {
        private const val TAG = "LogcatService" // 로그 태그
        private const val BROADCAST_LOG = "com.qe.adbrecorder.LOG_EVENT" // 로그 전송 브로드캐스트
        private const val BROADCAST_FILE_CREATED = "com.qe.adbrecorder.FILE_CREATED" // 파일 생성 알림 브로드캐스트
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true // 중복 실행 방지
            startForegroundWithNotification() // 포그라운드 서비스 시작
            startLogcat() // 로그 수집 시작
        }
        return START_STICKY // 시스템에 의해 중단된 경우 자동 재시작
    }

    @SuppressLint("ForegroundServiceType")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundWithNotification() {
        val channelId = "logcat_channel" // 알림 채널 ID
        val channel = NotificationChannel(channelId, "Logcat Service", NotificationManager.IMPORTANCE_LOW) // 알림 채널 생성
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel) // 알림 채널 등록

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ADB 로그 기록 중") // 알림 제목 설정
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 알림 아이콘 설정
            .build()

        startForeground(1, notification) // 서비스 포그라운드로 시작
    }

    private fun startLogcat() {
        logcatThread = Thread {
            var writer: BufferedWriter? = null // 로그 파일에 기록할 writer
            try {
                process = Runtime.getRuntime().exec("logcat -d") // logcat 명령 실행 (dump 모드)
                val reader = BufferedReader(InputStreamReader(process!!.inputStream)) // 출력 읽기 위한 reader

                var lastSwitchTime = System.currentTimeMillis() // 파일 분할 기준 시간 저장
                val initialStream = createLogOutputStream(this) // 초기 파일 생성

                if (initialStream == null) {
                    Log.e(TAG, "초기 로그 파일 생성 실패. 서비스 중지.") // 파일 생성 실패 시 서비스 중단
                    stopSelf()
                    return@Thread
                }

                var outputStream = initialStream.first // 출력 스트림
                writer = BufferedWriter(OutputStreamWriter(outputStream)) // writer 설정
                val initialPath = initialStream.second // 파일 경로 문자열
                notifyFileCreated(this, initialPath) // 액티비티에 파일 생성 메시지 전송

                var line: String? = "" // 읽을 로그 라인
                while (isRunning && reader.readLine().also { line = it } != null) {
                    val logLine = line ?: continue // null 방지
                    val now = System.currentTimeMillis()

                    if (now - lastSwitchTime > 30 * 60 * 1000) { // 30분마다 새 파일로 교체
                        writer?.flush()
                        writer?.close()

                        val result = createLogOutputStream(this) // 새 로그 파일 생성
                        if (result == null) {
                            Log.e(TAG, "새 로그 파일 생성 실패.") // 실패 시 루프 종료
                            break
                        }

                        outputStream = result.first
                        writer = BufferedWriter(OutputStreamWriter(outputStream))
                        notifyFileCreated(this, result.second) // 새 파일 생성 알림
                        lastSwitchTime = now
                    }

                    writer?.write(logLine) // 로그 파일에 기록
                    writer?.newLine()
                    writer?.flush()

                    sendLogToActivity(this, logLine) // 액티비티에 로그 라인 전송
                }

                reader.close() // reader 종료
                process?.destroy() // logcat 프로세스 종료
            } catch (e: Exception) {
                Log.e(TAG, "Logcat 수집 중 예외 발생", e)
            } finally {
                writer?.flush() // writer 정리
                writer?.close()
            }
        }

        logcatThread?.start() // 스레드 실행
    }

    private fun sendLogToActivity(context: Context, log: String) {
        val intent = Intent(BROADCAST_LOG) // 로그 전송 브로드캐스트 인텐트 생성
        intent.putExtra("log_line", log) // 로그 문자열 포함
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent) // 전송
    }

    private fun notifyFileCreated(context: Context, filePath: String) {
        val intent = Intent(BROADCAST_FILE_CREATED) // 파일 생성 알림 브로드캐스트
        intent.putExtra("file_message", filePath) // 파일 경로 문자열 포함
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent) // 전송
    }

    private fun createLogOutputStream(context: Context): Pair<OutputStream, String>? {
        return try {
            val dateFormat = SimpleDateFormat("yyMMdd", Locale.getDefault())       // YYMMDD
            val timeFormat = SimpleDateFormat("HHmmss", Locale.getDefault())       // HHmmss

            val datePart = dateFormat.format(Date())
            val timePart = timeFormat.format(Date())

            val fileName = "log_$timePart.txt"
            val folderName = datePart
            val relativePath = "Download/ADB_RECORDING/$folderName"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    val outputStream = resolver.openOutputStream(uri)
                    if (outputStream != null) {
                        val path = "$relativePath/$fileName" // 전체 경로 문자열
                        Log.i(TAG, "로그 파일 생성됨: $path") // 로그 출력
                        return Pair(outputStream, path) // 출력 스트림과 경로 반환
                    }
                }
            } else {
                Log.e(TAG, "Android 10 미만은 미지원") // 하위 버전 경고
            }

            null // 실패 시 null 반환
        } catch (e: Exception) {
            Log.e(TAG, "파일 출력 스트림 생성 실패", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false // 실행 상태 종료
        logcatThread?.interrupt() // 스레드 중단
        process?.destroy() // logcat 프로세스 종료
        Log.i(TAG, "LogcatService 중단됨") // 로그 출력
    }

    override fun onBind(intent: Intent?): IBinder? = null // 바인딩 인터페이스 미사용
}
