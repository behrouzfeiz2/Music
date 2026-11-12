package code.name.monkey.retromusic.fragments.about

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentAboutBinding
import code.name.monkey.retromusic.extensions.openUrl
import dev.chrisbanes.insetter.applyInsetter

class AboutFragment : Fragment(R.layout.fragment_about) {
    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAboutBinding.bind(view)

        binding.aboutContent.developerInfo?.setOnClickListener {
            openUrl("https://branex.ir")
        }
        binding.aboutContent.websiteInfo?.setOnClickListener {
            openUrl("https://branex.ir")
        }
        binding.aboutContent.telegramInfo?.setOnClickListener {
            openUrl("https://t.me/branex_ir")
        }
        binding.aboutContent.twitterInfo?.setOnClickListener {
            openUrl("https://x.com/branex_ir")
        }
        binding.aboutContent.instagramInfo?.setOnClickListener {
            openUrl("https://www.instagram.com/branex_ir")
        }

        binding.aboutContent.root.applyInsetter {
            type(navigationBars = true) {
                padding(vertical = true)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
