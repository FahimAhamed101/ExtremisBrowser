package com.extremis.browser

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.extremis.browser.databinding.DialogVideoDownloadBinding
import com.extremis.browser.models.VideoInfo
import com.extremis.browser.models.VideoQuality
import com.extremis.browser.utils.VideoDownloader
import kotlinx.coroutines.launch

class VideoDownloadDialogFragment : DialogFragment() {

    private var _binding: DialogVideoDownloadBinding? = null
    private val binding get() = _binding!!

    private lateinit var videoDownloader: VideoDownloader
    private var videoInfo: VideoInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoDownloader = VideoDownloader(requireContext())
        @Suppress("DEPRECATION")
        videoInfo = arguments?.getParcelable(ARG_VIDEO_INFO)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogVideoDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val info = videoInfo ?: run { dismiss(); return }

        binding.tvVideoTitle.text = info.title
        binding.tvVideoUrl.text = info.url

        binding.btnDownload.setOnClickListener { downloadSelected() }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnRefreshQualities.setOnClickListener { loadQualities() }

        if (info.qualities.isNotEmpty()) {
            displayQualities(info.qualities)
        } else {
            loadQualities()
        }
    }

    private fun loadQualities() {
        val info = videoInfo ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.qualityGroup.visibility = View.GONE
        binding.btnDownload.isEnabled = false

        lifecycleScope.launch {
            try {
                val qualities = videoDownloader.extractVideoQualities(info.url)
                if (qualities.isNotEmpty()) {
                    videoInfo = info.copy(qualities = qualities)
                    displayQualities(qualities)
                } else {
                    Toast.makeText(requireContext(), "No downloadable video found", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun displayQualities(qualities: List<VideoQuality>) {
        binding.qualityGroup.removeAllViews()
        binding.qualityGroup.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = true

        val sorted = qualities.sortedByDescending { it.height.toIntOrNull() ?: 0 }
        sorted.forEachIndexed { index, q ->
            val rb = RadioButton(requireContext()).apply {
                id = index
                text = q.label
                tag = q
                if (index == 0) isChecked = true
            }
            binding.qualityGroup.addView(rb)
        }
    }

    private fun downloadSelected() {
        val info = videoInfo ?: return
        val id = binding.qualityGroup.checkedRadioButtonId
        if (id == -1) {
            Toast.makeText(requireContext(), "Select a quality", Toast.LENGTH_SHORT).show()
            return
        }

        val quality = binding.qualityGroup.findViewById<RadioButton>(id).tag as VideoQuality
        videoDownloader.downloadVideo(info, quality)
        Toast.makeText(requireContext(), "Download started: ${quality.label}", Toast.LENGTH_SHORT).show()
        dismiss()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply { setCanceledOnTouchOutside(true) }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_VIDEO_INFO = "video_info"

        fun newInstance(videoInfo: VideoInfo) = VideoDownloadDialogFragment().apply {
            arguments = Bundle().apply { putParcelable(ARG_VIDEO_INFO, videoInfo) }
        }
    }
}
