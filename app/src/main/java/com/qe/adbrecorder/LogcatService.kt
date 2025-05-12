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

    private var isRunning = false // 로그 수집 상태
    private var logcatThread: Thread? = null // logcat 수집 스레드
    private var process: Process? = null // logcat 프로세스

    companion object {
        private const val TAG = "LogcatService" // 로그 태그
        private const val BROADCAST_ACTION = "com.qe.adbrecorder.LOG_EVENT" // 브로드캐스트 액션
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true // 중복 실행 방지
            startForegroundWithNotification() // 포그라운드 서비스 시작
            startLogcat() // logcat 수집 시작
        }
        return START_STICKY // 시스템에 의해 재시작되도록 설정
    }

    @SuppressLint("ForegroundServiceType")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundWithNotification() {
        val channelId = "logcat_channel" // 알림 채널 ID
        val channel = NotificationChannel(
            channelId,
            "Logcat Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel) // 알림 채널 등록

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ADB 로그 기록 중") // 알림 제목
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 아이콘 설정
            .build()

        startForeground(1, notification) // 포그라운드로 실행
    }

    private fun startLogcat() {
        logcatThread = Thread {
            var writer: BufferedWriter? = null
            try {
                process = Runtime.getRuntime().exec("logcat") // logcat 실행
                val reader = BufferedReader(InputStreamReader(process!!.inputStream)) // 로그 읽기 스트림

                var lastSwitchTime = System.currentTimeMillis() // 파일 분할 기준 시간
                val initialStream = createLogOutputStream(this) // 초기 파일 생성

                if (initialStream == null) {
                    Log.e(TAG, "초기 로그 파일 생성 실패. 서비스 중지.")
                    stopSelf()
                    return@Thread
                }

                var outputStream = initialStream.first // 출력 스트림
                writer = BufferedWriter(OutputStreamWriter(outputStream)) // 파일 쓰기 설정

                var line: String? = ""
                while (isRunning && reader.readLine().also { line = it } != null) {
                    val logLine = line ?: continue // null 방지
                    val now = System.currentTimeMillis()

                    if (now - lastSwitchTime > 30 * 60 * 1000) { // 30분마다 새 파일 생성
                        writer?.flush()
                        writer?.close()

                        val result = createLogOutputStream(this)
                        if (result == null) {
                            Log.e(TAG, "새 로그 파일 생성 실패.")
                            break
                        }

                        outputStream = result.first
                        writer = BufferedWriter(OutputStreamWriter(outputStream))
                        lastSwitchTime = now
                    }

                    writer?.write(logLine) // 로그 파일에 쓰기
                    writer?.newLine()
                    writer?.flush()

                    sendLogToActivity(this, logLine) // 액티비티로 전송
                }

                reader.close() // reader 종료
                process?.destroy() // 프로세스 종료
            } catch (e: Exception) {
                Log.e(TAG, "Logcat 수집 중 예외 발생", e)
            } finally {
                writer?.flush() // 마지막 남은 로그 기록
                writer?.close()
            }
        }

        logcatThread?.start() // 스레드 실행
    }

    private fun sendLogToActivity(context: Context, log: String) {
        val intent = Intent(BROADCAST_ACTION) // 브로드캐스트 인텐트 생성
        intent.putExtra("log_line", log) // 로그 삽입
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent) // 전송
    }

    private fun createLogOutputStream(context: Context): Pair<OutputStream, String>? {
        return try {
            val directoryTimeFormat = SimpleDateFormat("yyMMdd", Locale.getDefault())
            val fileTimeFormat = SimpleDateFormat("yyMMdd_HHmmss", Locale.getDefault()) // 시간 포맷
            val directoryTime = directoryTimeFormat.format(Date())
            val fileTime = fileTimeFormat.format(Date())
            val fileName = "log_${fileTime}.txt" // 파일명
            val folderName = "ADB_RECORDING_${directoryTime}" // 폴더명
            val relativePath = "Download/$folderName" // 경로

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10 이상
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
                        Log.i(TAG, "로그 파일 생성됨: $fileName")
                        return Pair(outputStream, fileName)
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
        isRunning = false // 플래그 리셋
        logcatThread?.interrupt() // 스레드 중단
        process?.destroy() // 프로세스 종료
        Log.i(TAG, "LogcatService 중단됨")
    }

    override fun onBind(intent: Intent?): IBinder? = null // 바인딩 사용 안함
}