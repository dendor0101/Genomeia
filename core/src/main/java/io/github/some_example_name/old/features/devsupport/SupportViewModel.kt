package io.github.some_example_name.old.features.devsupport

import io.github.some_example_name.old.commands.GameCommand
import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.SessionJournal
import kotlinx.serialization.Serializable

data class CryptoWallet(
    val title: String,
    val address: String,
    val qrFile: String
)

/** Что игрок хочет сделать на экране «Об игре». */
@Serializable
sealed interface SupportIntent : GameCommand {
    @Serializable
    data class OpenLink(val id: String) : SupportIntent {
        override val name get() = "OpenLink"
        override val detail get() = id
    }

    /**
     * @param title именно название кошелька, а не адрес: в журнал не кладут содержимое
     *   буфера обмена, даже когда оно публичное — это плохая привычка на будущее.
     */
    @Serializable
    data class CopyAddress(val title: String) : SupportIntent {
        override val name get() = "CopyAddress"
        override val detail get() = title
    }
}

class SupportViewModel {

    // Идентификаторы и старые команды поддержки сохранены для воспроизведения журналов.
    val links = linkedMapOf(
        "youtube" to "https://www.youtube.com/@Genomeia-project",
        "discord" to "https://discord.com/invite/HRajjtbENs",
        "tiktok" to "https://www.tiktok.com/@genomeia",
        "itch" to "https://genomeia-project.itch.io/genomeia",
        "github" to "https://github.com/dendor0101/Genomeia",
        "donate" to "https://genomeia-project.itch.io/genomeia/purchase"
    )

    val wallets: List<CryptoWallet> = listOf(
        CryptoWallet(
            title = "USDT (TRC20 / TRON)",
            address = "TXVmZKM8K5NFcfJpYMgpWm9MpaLPADoC7f",
            qrFile = "ui/trc-20-qr.png"
        ),
        CryptoWallet(
            title = "TON",
            address = "UQANA9T_wuxvg73xQz-N7e-WfzDAf5uwMT0f6HIBQGCwEjBO",
            qrFile = "ui/ton-qr.png"
        )
    )

    fun handle(intent: SupportIntent) = SessionJournal.execute(CommandSource.SUPPORT, intent) { apply(it) }

    private fun apply(intent: SupportIntent) {
        when (intent) {
            is SupportIntent.OpenLink -> {
                val url = links[intent.id] ?: return
                if (!BugReplay.isActive) Gdx.net.openURI(url)
            }
            is SupportIntent.CopyAddress -> {
                val wallet = wallets.firstOrNull { it.title == intent.title } ?: return
                Gdx.app.clipboard.contents = wallet.address
            }
        }
    }
}
