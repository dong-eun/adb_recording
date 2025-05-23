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

    private val folderLoggedSet = mutableSetOf<String>() // 클래스 상단에 선언 (중복 로그 방지용)

    companion object {
        private const val TAG = "LogcatService" // 로그 태그
//        private const val BROADCAST_LOG = "com.qe.adbrecorder.LOG_EVENT" // 로그 전송 브로드캐스트 -> 미사용
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
            .setContentTitle("ADB log recording") // 알림 제목 설정
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 알림 아이콘 설정
            .build()

        startForeground(1, notification) // 서비스 포그라운드로 시작
    }

    private fun startLogcat() {

        val cmd = arrayOf("logcat", "-v", "threadtime")

        logcatThread = Thread {
            var writer: BufferedWriter? = null // 로그 파일에 기록할 writer
            try {
                process = Runtime.getRuntime().exec(cmd) // logcat 명령 실행
                val reader = BufferedReader(InputStreamReader(process!!.inputStream)) // 출력 읽기 위한 reader

                var lastSwitchTime = System.currentTimeMillis() // 파일 분할 기준 시간 저장
                val initialStream = createLogOutputStream(this) // 초기 파일 생성

                if (initialStream == null) {
                    Log.e(TAG, "Failed to create log file, Service stopped") // 파일 생성 실패 시 서비스 중단
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
                            Log.e(TAG, "Failed to create log file") // 실패 시 루프 종료
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

//                    sendLogToActivity(this, logLine) // 액티비티에 로그 라인 전송 -> 미사용으로 주석
                }

                reader.close() // reader 종료
                process?.destroy() // logcat 프로세스 종료
            } catch (e: Exception) {
                Log.e(TAG, "Logcat exception occurred", e)
            } finally {
                writer?.flush() // writer 정리
                writer?.close()
            }
        }

        logcatThread?.start() // 스레드 실행
    }

    // 미사용으로 주석 처리
//    private fun sendLogToActivity(context: Context, log: String) {
//        val intent = Intent(BROADCAST_LOG) // 로그 전송 브로드캐스트 인텐트 생성
//        intent.putExtra("log_line", log) // 로그 문자열 포함
//        LocalBroadcastManager.getInstance(context).sendBroadcast(intent) // 전송
//    }

    private fun notifyFileCreated(context: Context, filePath: String) {
        val intent = Intent(BROADCAST_FILE_CREATED) // 파일 생성 알림 브로드캐스트
        intent.putExtra("file_message", filePath) // 파일 경로 문자열 포함
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent) // 전송
    }

    private fun createLogOutputStream(context: Context): Pair<OutputStream, String>? {
        return try {
            val dateFormat = SimpleDateFormat("yyMMdd", Locale.getDefault())       // 날짜 형식 지정
            val timeFormat = SimpleDateFormat("HHmmss", Locale.getDefault())       // 시간 형식 지정

            val datePart = dateFormat.format(Date()) // 현재 날짜를 YYMMDD 형식으로 포맷
            val timePart = timeFormat.format(Date()) // 현재 시간을 HHmmss 형식으로 포맷

            val fileName = "log_$timePart.txt"       // 로그 파일 이름 지정
            val folderName = datePart                // 날짜 기반 폴더 이름 지정
            val relativePath = "Download/ADB_RECORDING/$folderName" // 다운로드 내 저장 경로 지정
            val fullPath = "$relativePath/$fileName" // 전체 파일 경로 생성

            Log.i(TAG, "Attempting to create file at: $fullPath") // 파일 생성 시도 로그 출력

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // 안드로이드 10 이상에서만 MediaStore 사용 가능
                val resolver = context.contentResolver // ContentResolver를 통해 MediaStore 접근

                // 폴더에 대해 최초 접근 시 로그 출력 (중복 출력 방지용)
                if (!folderLoggedSet.contains(relativePath)) {
                    Log.i(TAG, "Target folder (MediaStore): $relativePath") // 폴더 경로 로그 출력
                    folderLoggedSet.add(relativePath) // 이미 로그를 출력한 폴더는 기록하여 중복 방지
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)       // 파일 이름 설정
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")      // 파일 형식 설정 (텍스트)
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)  // 저장 위치 설정
                }

                // MediaStore에 파일 정보 삽입
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) { // URI가 유효한 경우
                    val outputStream = resolver.openOutputStream(uri) // 출력 스트림 열기
                    if (outputStream != null) {
                        Log.i(TAG, "File created successfully: $fullPath") // 성공 로그 출력
                        return Pair(outputStream, fullPath) // 출력 스트림과 파일 경로 반환
                    } else {
                        Log.e(TAG, "Failed to open output stream: $fullPath") // 출력 스트림 열기 실패
                    }
                } else {
                    Log.e(TAG, "Failed to insert into MediaStore: $fullPath") // 파일 삽입 실패
                }
            } else {
                Log.e(TAG, "Android version below 10 is not supported") // Android 10 미만은 지원하지 않음
            }

            null // 실패 시 null 반환
        } catch (e: Exception) {
            Log.e(TAG, "Exception while creating file output stream", e) // 예외 발생 시 로그 출력
            null // 실패 시 null 반환
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false // 로그 수집 상태 플래그 비활성화

        logcatThread?.interrupt() // 로그 수집 스레드에 인터럽트 신호 전달
        try {
            logcatThread?.join(1000) // 최대 1초간 스레드가 종료되길 기다림
        } catch (e: Exception) {
            Log.w(TAG, "Error while waiting for logcatThread to terminate", e) // 예외 발생 시 경고 로그 출력
        }

        process?.destroy() // logcat 프로세스 종료 요청
        try {
            process?.waitFor() // 프로세스가 완전히 종료될 때까지 대기
        } catch (e: Exception) {
            Log.w(TAG, "Error while waiting for logcatThread to terminate", e) // 예외 발생 시 경고 로그 출력
        }

        Log.i(TAG, "LogcatService stopped") // 서비스 중단 로그 출력
    }

    override fun onBind(intent: Intent?): IBinder? = null // 바인딩 인터페이스 미사용
}
