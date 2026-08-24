package io.github.some_example_name.old.features.devsupport

import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.core.log.ActionLog

data class CryptoWallet(
    val title: String,
    val address: String,
    val qrFile: String
)

/** Что игрок хочет сделать на экране поддержки. */
sealed interface SupportIntent {
    val name: String
    val detail: String get() = ""

    /**
     * @param title именно название кошелька, а не адрес: в журнал не кладут содержимое
     *   буфера обмена, даже когда оно публичное — это плохая привычка на будущее.
     */
    data class CopyAddress(val title: String) : SupportIntent {
        override val name get() = "CopyAddress"
        override val detail get() = title
    }
}

class SupportViewModel {

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

    fun handle(intent: SupportIntent) {
        ActionLog.record(LOG_SOURCE, intent.name, intent.detail)

        when (intent) {
            is SupportIntent.CopyAddress -> {
                val wallet = wallets.firstOrNull { it.title == intent.title } ?: return
                Gdx.app.clipboard.contents = wallet.address
            }
        }
    }

    private companion object {
        const val LOG_SOURCE = "Support"
    }
}
