package com.example.comicdownloader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var tvStatus: TextView
    private lateinit var recyclerView: RecyclerView
    private val downloadAnnouncements = mutableListOf<DownloadAnnouncement>()
    private lateinit var adapter: DownloadAnnouncementAdapter

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        btnDownload = findViewById(R.id.btnDownload)
        tvStatus = findViewById(R.id.tvStatus)
        recyclerView = findViewById(R.id.recyclerView)

        adapter = DownloadAnnouncementAdapter(downloadAnnouncements)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        selectedDirectoryUri = getSelectedDirectoryUri()

        btnDownload.setOnClickListener { view ->
            if (selectedDirectoryUri == null) {
                openDirectoryPicker()
                onDownloadButtonClick(view)
            } else {
                onDownloadButtonClick(view)
            }
        }
    }

    private fun saveSelectedDirectoryUri(uri: Uri) {
        val sharedPreferences: SharedPreferences =
            getSharedPreferences("comic_downloader_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().putString("selected_directory_uri", uri.toString()).apply()
    }

    private fun getSelectedDirectoryUri(): Uri? {
        val sharedPreferences: SharedPreferences =
            getSharedPreferences("comic_downloader_prefs", Context.MODE_PRIVATE)
        val uriString = sharedPreferences.getString("selected_directory_uri", null)
        return uriString?.let { Uri.parse(it) }
    }

    private val openDirectoryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                selectedDirectoryUri = it
                saveSelectedDirectoryUri(it)
            }
        }

    private var selectedDirectoryUri: Uri? = null

    private fun openDirectoryPicker() {
        try {
            openDirectoryLauncher.launch(null)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No file manager found", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun onDownloadButtonClick(view: View) {
        val data = etUrl.text.toString()
        // If the user enters multiple URLs, find all with regex: https?://\S+
        val regex = Regex("https?://\\S+") // Find all URLs
        val urls = regex.findAll(data).map { it.value }.toList()
        for (url in urls) {
            if (url.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    startDownload(url)
                }
            } else {
                Toast.makeText(this, R.string.toast_enter_url, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        val uri = URI(url)
        val urlObj = URL(uri.toASCIIString())
        return "${urlObj.protocol}://${urlObj.host}"
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("SetTextI18n")
    private suspend fun startDownload(url: String) {
        try {
            val encodedUrl = URI(url).toASCIIString()
            val baseUrl = getBaseUrl(encodedUrl)
            println("baseUrl: $baseUrl")
            withContext(Dispatchers.Main) {
                tvStatus.text = getString(R.string.status_downloading)
            }
            try {
                val urls = getChapterUrls(encodedUrl, baseUrl).reversed()
                println("Chapter URLs: $urls")
                if (urls.isNotEmpty()) {
                    val folderPath = createFolderFromTitle(encodedUrl, baseUrl)
                    val title = folderPath.split("/").last()
                    if (folderPath.isEmpty()) {
                        updateStatus(R.string.status_idle)
                        return
                    }
                    println("Folder path: $folderPath")
                    var idx = 0
                    urls.forEachIndexed { _, chapterUrl ->
                        val imgUrls = getImageUrlsFromChapter(chapterUrl, baseUrl)
                        println("Image URLs: $imgUrls")
                        imgUrls.forEachIndexed { _, imgUrl ->
                            downloadImage(imgUrl, folderPath, idx, baseUrl)
                            idx++
                        }
                    }
                    updateStatus(R.string.status_complete)
                } else {
                    updateStatus(R.string.status_error, "No chapters found")
                }
            } catch (e: Exception) {
                updateStatus(R.string.status_error, e.message)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                tvStatus.text = getString(R.string.status_error, e.message)
            }
        }
    }

    private suspend fun updateStatus(statusResId: Int, vararg formatArgs: Any?) {
        withContext(Dispatchers.Main) {
            tvStatus.text = getString(statusResId, *formatArgs)
        }
    }

    private fun getChapterUrls(url: String, baseUrl: String): List<String> {
        // Parse the URL to get the base URL
        val uri = URI(url)
        // Split the path to extract the ID and slug
        val pathParts = uri.path.trim('/').split("-")
        val idPart = pathParts[0]
        val slugPart =
            pathParts.drop(2).joinToString("-").replace("truyen-", "").replace(".html", "")
        val reqUrl = "$baseUrl/list-showchapter.php?idchapshow=$idPart&idlinkanime=$slugPart"

        val headers = mapOf(
            "Referer" to baseUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3",
            "charset" to "UTF-8"
        )

        println(headers)

        val doc = Jsoup.connect(reqUrl).header("Referer", baseUrl).get()
        return doc.select("a[href]").map { it.absUrl("href") }
    }

    private fun getImageUrlsFromChapter(chapterUrl: String, baseUrl: String): List<String> {
        val doc = Jsoup.connect(chapterUrl).header("Referer", baseUrl).get()
        doc.charset(Charset.forName("UTF-8"))
        return doc.select("img[data-src]").map { it.absUrl("data-src") }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createFolderFromTitle(url: String, baseUrl: String): String {
        return try {
            val title = Jsoup.connect(url).header("Referer", baseUrl).get().title()
                .replace(Regex("[:*?\"<>|]"), "")
            println("Title: $title")

            // Update the RecyclerView
            runOnUiThread {
                downloadAnnouncements.add(DownloadAnnouncement("Downloading: $title"))
                adapter.notifyItemInserted(downloadAnnouncements.size - 1)
                recyclerView.scrollToPosition(downloadAnnouncements.size - 1)
            }

            selectedDirectoryUri?.let { uri ->
                val documentFile = DocumentFile.fromTreeUri(this, uri)
                val folder = documentFile?.createDirectory(title)
                folder?.uri?.toString() ?: ""
            } ?: ""
        } catch (e: Exception) {
            println("Error creating folder: ${e.message}")
            ""
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun downloadImage(imgUrl: String, folderPath: String, imgIdx: Int, baseUrl: String) {
        try {
            val url = URL(imgUrl)
            val connection = url.openConnection()
            connection.setRequestProperty("Referer", baseUrl)
            connection.connect()

            val input = connection.getInputStream()

            // Use DocumentFile to create the file
            val documentFile = DocumentFile.fromTreeUri(this, Uri.parse(folderPath))
            val fileName = String.format("%03d.jpg", imgIdx + 1)
            val file = documentFile?.createFile("image/jpeg", fileName)
            println("img Url Downloaded: $imgUrl")

            if (file != null) {
                val outputStream = contentResolver.openOutputStream(file.uri)
                if (outputStream != null) {
                    input.copyTo(outputStream)
                    outputStream.close()
                }
            }

            input.close()

            // Update the RecyclerView
            runOnUiThread {
                downloadAnnouncements.add(DownloadAnnouncement("Downloaded: $fileName"))
                adapter.notifyItemInserted(downloadAnnouncements.size - 1)
                recyclerView.scrollToPosition(downloadAnnouncements.size - 1)
            }
        } catch (e: Exception) {
            println("Error downloading image: $e")
            e.printStackTrace()
        }
    }
}