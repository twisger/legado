package io.legado.app.ui.book.read

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.DialogContentEditBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.applyTint
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

import java.util.concurrent.TimeUnit

/**
 * 内容编辑
 */
class ContentEditDialog : BaseDialogFragment(R.layout.dialog_content_edit) {
    private companion object {
        const val DEEPSEEK_API_URL = "https://api.deepseek.com/chat/completions"
        const val DEEPSEEK_API_KEY = ""
        const val PROMPT_PREFIX = "请你补充这段小说中没有详细描写的性爱细节（包括但不限于口交，乳交，正面位，后入位、骑乘位，侧卧位等体位的性交或调情，需根据剧情和上下文判断，种类尽可能多），保持文笔，风格，人物形象和原文一致，要非常详细的描写内容，动作，神态，微表情，微动作，场景交互，服饰细节，身体反应，对话，各种部位的外观(包括但不限于阴茎，睾丸，乳房，精液等性器官的大小，形状，质感，色泽等细节)，以及性高潮，射精等动作的详细描写来增加香艳程度。没有篇幅限制，不要省略任何详细描写，补充的内容要尽可能长，不少于3000字。直接输出补充的部分，不要任何带无关的解释和说明。\n"
    }
    private var isStreaming = false
    private val streamBuffer = StringBuilder()
    private var initialCursorPos = 0


    val binding by viewBinding(DialogContentEditBinding::bind)
    val viewModel by viewModels<ContentEditViewModel>()

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = ReadBook.curTextChapter?.title
        initMenu()
        binding.toolBar.setOnClickListener {
            lifecycleScope.launch {
                val book = ReadBook.book ?: return@launch
                val chapter = withContext(IO) {
                    appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                } ?: return@launch
                editTitle(chapter)
            }
        }
        viewModel.loadStateLiveData.observe(viewLifecycleOwner) {
            if (it) {
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.gone()
            }
        }
        viewModel.initContent {
            binding.contentView.setText(it)
            binding.contentView.post {
                binding.contentView.apply {
                    val lineIndex = layout.getLineForOffset(ReadBook.durChapterPos)
                    val lineHeight = layout.getLineTop(lineIndex)
                    scrollTo(0, lineHeight)
                }
            }
        }
    }

    private fun getDeepSeekApiKey(): String {
        // 从安全存储（如EncryptedSharedPreferences）获取
        return DEEPSEEK_API_KEY// 临时测试用
    }

    private fun handleAISupplement() {
        if (isStreaming) {
            context?.toastOnUi("正在生成中...")
            return
        }
        // 获取当前全文和光标位置
        val fullText = binding.contentView.text.toString()
        val cursorPos = binding.contentView.selectionStart
        initialCursorPos = cursorPos

        // 在光标位置插入标记
        val markedText = StringBuilder(fullText).apply {
            insert(cursorPos, "【在这里补充内容】")
        }.toString()

        val requestBody = JsonObject().apply {
            addProperty("model", "deepseek-reasoner")

            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", "你是一个专业的小说编辑助手，需要根据用户标记的位置补充合适的内容")
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", PROMPT_PREFIX + markedText)
                })
            }

            add("messages", messages)
            addProperty("stream", true)
        }.toString()
        // 显示加载状态
        isStreaming = true
        streamBuffer.clear()
        viewModel.loadStateLiveData.postValue(true)
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS) // 取消读取超时
            .build()
        val request = Request.Builder()
            .url(DEEPSEEK_API_URL)
            .addHeader("Authorization", "Bearer ${getDeepSeekApiKey()}")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                lifecycleScope.launch {
                    isStreaming = false
                    viewModel.loadStateLiveData.postValue(false)
                    context?.toastOnUi("请求失败: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    lifecycleScope.launch {
                        isStreaming = false
                        viewModel.loadStateLiveData.postValue(false)
                        context?.toastOnUi("API错误: ${response.code}")
                    }
                    return
                }

// 修改流式处理部分
                response.body?.let { body ->
                    val source = body.source()
                    try {
                        while (isStreaming) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.startsWith("data: [DONE]") -> break
                                line.startsWith("data: ") -> {
                                    val jsonStr = line.substring(6)
                                    if (jsonStr.isBlank()) continue

                                    try {
                                        val json = JSONObject(jsonStr)
                                        val choices = json.getJSONArray("choices")
                                        if (choices.length() == 0) continue

                                        val choice = choices.getJSONObject(0)
                                        val delta = choice.getJSONObject("delta")

                                        // 推理内容
                                        val reasoning_content = delta.optString("reasoning_content", "")
                                        val _content = delta.optString("content", "")
                                        val content = if (_content == "null") "" else _content
                                        val finishReason = choice.optString("finish_reason", null)

                                        if (content.isNotEmpty()) {
                                            lifecycleScope.launch(Dispatchers.Main) {
                                                streamBuffer.append(content)
                                                // 实时插入到当前光标位置
                                                binding.contentView.editableText.insert(
                                                    initialCursorPos + streamBuffer.length - content.length,
                                                    content
                                                )
                                            }
                                        }

                                        if (finishReason == "stop") {
                                            lifecycleScope.launch(Dispatchers.Main) {
                                                // 最终处理逻辑
                                                binding.contentView.setSelection(
                                                    initialCursorPos + streamBuffer.length
                                                )
                                            }
                                            break
                                        }
                                    } catch (e: JSONException) {
                                        Log.e("Stream", "JSON解析失败: $line")
                                        context?.toastOnUi("数据格式异常")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 异常处理...
                    } finally {
                        viewModel.loadStateLiveData.postValue(false)
                    }
                }
            }
        })
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.content_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_save -> {
                    save()
                    dismiss()
                }
                R.id.menu_reset -> viewModel.initContent(true) { content ->
                    binding.contentView.setText(content)
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
                R.id.menu_copy_all -> requireContext()
                    .sendToClip("${binding.toolBar.title}\n${binding.contentView.text}")
                R.id.menu_ai_supplement -> {
                    handleAISupplement()
                }
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun editTitle(chapter: BookChapter) {
        alert {
            setTitle(R.string.edit)
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater)
            alertBinding.editView.setText(chapter.title)
            setCustomView(alertBinding.root)
            okButton {
                chapter.title = alertBinding.editView.text.toString()
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookChapterDao.update(chapter)
                    }
                    binding.toolBar.title = chapter.getDisplayTitle()
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        save()
    }

    private fun save() {
        val content = binding.contentView.text?.toString() ?: return
        Coroutine.async {
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao
                .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@async
            BookHelp.saveText(book, chapter, content)
            ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
        }
    }

    class ContentEditViewModel(application: Application) : BaseViewModel(application) {
        val loadStateLiveData = MutableLiveData<Boolean>()
        var content: String? = null

        fun initContent(reset: Boolean = false, success: (String) -> Unit) {
            execute {
                val book = ReadBook.book ?: return@execute null
                val chapter = appDb.bookChapterDao
                    .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                    ?: return@execute null
                if (reset) {
                    content = null
                    BookHelp.delContent(book, chapter)
                    if (!book.isLocal) ReadBook.bookSource?.let { bookSource ->
                        WebBook.getContentAwait(bookSource, book, chapter)
                    }
                }
                return@execute content ?: let {
                    val contentProcessor = ContentProcessor.get(book.name, book.origin)
                    val content = BookHelp.getContent(book, chapter) ?: return@let null
                    contentProcessor.getContent(book, chapter, content, includeTitle = false)
                        .toString()
                }
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onSuccess {
                content = it
                success.invoke(it ?: "")
            }.onFinally {
                loadStateLiveData.postValue(false)
            }
        }
    }

}