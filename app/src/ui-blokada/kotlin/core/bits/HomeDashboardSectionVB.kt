package core.bits

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.github.michaelbull.result.get
import com.github.salomonbrys.kodein.instance
import core.*
import core.bits.menu.isLandscape
import gs.presentation.ListViewBinder
import gs.presentation.NamedViewBinder
import gs.presentation.ViewBinder
import gs.property.I18n
import gs.property.Repo
import gs.property.Version
import org.blokada.BuildConfig
import org.blokada.R
import tunnel.BLOCKA_CONFIG
import tunnel.BlockaConfig
import tunnel.showSnack
import update.UpdateCoordinator
import java.util.*

data class SlotsSeenStatus(
        val intro: Boolean = false,
        val telegram: Boolean = false,
        val blog: Boolean = false,
        val updated: Int = 0,
        val cta: Int = 0,
        val donate: Int = 0
)

class SlotStatusPersistence {
    val load = { ->
        Result.of { Persistence.paper().read<SlotsSeenStatus>("slots:status", SlotsSeenStatus()) }
    }
    val save = { slots: SlotsSeenStatus ->
        Result.of { Persistence.paper().write("slots:status", slots) }
    }
}

class HomeDashboardSectionVB(
        val ktx: AndroidKontext,
        val ctx: Context = ktx.ctx,
        val version: Version = ktx.di().instance(),
        val welcome: Welcome = ktx.di().instance(),
        val repo: Repo = ktx.di().instance(),
        val manager: TunnelStateManager = ktx.di().instance(),
        override val name: Resource = R.string.panel_section_home.res()
) : ListViewBinder(), NamedViewBinder {

    override fun attach(view: VBListView) {
        ktx.on(BLOCKA_CONFIG, listener)
        if (isLandscape(ktx.ctx)) {
            view.enableLandscapeMode(reversed = false)
            view.set(items)
        } else view.set(items)
    }

    override fun detach(view: VBListView) {
        ktx.cancel(BLOCKA_CONFIG, listener)
    }

    private val update = {
        view?.run {
            val noSubscription = cfg.activeUntil.before(Date())
            val (slot, name) = decideOnSlot(noSubscription)
            if (slot != null && added == null) {
                items = listOf(slot) + items
                added = name
                if (slot is SimpleByteVB) slot.onTapped = {
                    // Remove this slot
                    markAsSeen()

                    if (!slot.shouldKeepAfterTap) {
                        items = items.subList(1, items.size)
                        set(items)
                    }
                }
            }
            set(items)
            if (isLandscape(ktx.ctx)) {
                enableLandscapeMode(reversed = false)
                set(items)
            }
        }
    }

    private var items = listOf<ViewBinder>(
            MasterSwitchVB(ktx),
            AdsBlockedVB(ktx),
            VpnStatusVB(ktx),
            ActiveDnsVB(ktx),
            ShareVB(ktx)
    )

    private val listener = { config: BlockaConfig ->
        cfg = config
        update()
        Unit
    }

    private var cfg: BlockaConfig = BlockaConfig()
    private var added: OneTimeByte? = null
    private val oneTimeBytes = createOneTimeBytes(ktx)

    private fun markAsSeen() {
        val cfg = Persistence.slots.load().get()!!
        val newCfg = when (added) {
            OneTimeByte.UPDATED -> cfg.copy(updated = BuildConfig.VERSION_CODE)
            OneTimeByte.DONATE -> cfg.copy(donate = BuildConfig.VERSION_CODE)
            else -> cfg
        }
        Persistence.slots.save(newCfg)
    }

    private fun decideOnSlot(noSubscription: Boolean): Pair<ViewBinder?, OneTimeByte?> {
        val cfg = Persistence.slots.load().get()
        val name = if (cfg == null) null else when {
            //isLandscape(ktx.ctx) -> null
            isUpdate(ctx, repo.content().newestVersionCode) -> OneTimeByte.UPDATE_AVAILABLE
            BuildConfig.VERSION_CODE > cfg.updated -> OneTimeByte.UPDATED
            (BuildConfig.VERSION_CODE > cfg.donate) && noSubscription -> OneTimeByte.DONATE
            version.obsolete() -> OneTimeByte.OBSOLETE
            getInstalledBuilds().size > 1 -> OneTimeByte.CLEANUP
            else -> null
        }
        return oneTimeBytes[name] to name
    }

    private fun getInstalledBuilds(): List<String> {
        return welcome.conflictingBuilds().map {
            if (isPackageInstalled(it)) it else null
        }.filterNotNull()
    }

    private fun isPackageInstalled(appId: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(appId) as Intent? ?: return false
        val activities = ctx.packageManager.queryIntentActivities(intent, 0)
        return activities.size > 0
    }
}

class VpnVB(
        private val ktx: AndroidKontext,
        private val s: Tunnel = ktx.di().instance(),
        private val tunManager: TunnelStateManager = ktx.di().instance()
) : BitVB() {

    override fun attach(view: BitView) {
        ktx.on(BLOCKA_CONFIG, configListener)
        update()
    }

    override fun detach(view: BitView) {
        ktx.cancel(BLOCKA_CONFIG, configListener)
    }

    private var config: BlockaConfig = BlockaConfig()
    private val configListener = { cfg: BlockaConfig ->
        config = cfg
        update()
        Unit
    }

    private val update = {
        view?.apply {
            if (config.blockaVpn) {
                label(R.string.home_vpn_enabled.res())
                icon(R.drawable.ic_verified.res(), color = R.color.switch_on.res())
            } else {
                label(R.string.home_vpn_disabled.res())
                icon(R.drawable.ic_shield_outline.res())
            }
            switch(config.blockaVpn)
            onSwitch { turnOn ->
                tunManager.turnVpn(turnOn)
            }
        }
        Unit
    }
}

class Adblocking2VB(
        private val ktx: AndroidKontext,
        private val s: Tunnel = ktx.di().instance()
) : BitVB() {

    override fun attach(view: BitView) {
        ktx.on(BLOCKA_CONFIG, configListener)
        update()
    }

    override fun detach(view: BitView) {
        ktx.cancel(BLOCKA_CONFIG, configListener)
    }

    private var config: BlockaConfig = BlockaConfig()
    private val configListener = { cfg: BlockaConfig ->
        config = cfg
        update()
        Unit
    }

    private val update = {
        view?.apply {
            if (config.adblocking) {
                label(R.string.home_adblocking_enabled.res())
                icon(R.drawable.ic_blocked.res(), color = R.color.switch_on.res())
            } else {
                label(R.string.home_adblocking_disabled.res())
                icon(R.drawable.ic_show.res())
            }
            switch(config.adblocking)
            onSwitch { adblocking ->
                if (!adblocking && !config.blockaVpn) s.enabled %= false
                ktx.emit(BLOCKA_CONFIG, config.copy(adblocking = adblocking))
            }
        }
        Unit
    }

}

class SimpleByteVB(
        private val ktx: AndroidKontext,
        private val label: Resource,
        private val description: Resource,
        val shouldKeepAfterTap: Boolean = false,
        private val onTap: (ktx: AndroidKontext) -> Unit,
        var onTapped: () -> Unit = {}
) : ByteVB() {
    override fun attach(view: ByteView) {
        view.icon(null)
        view.label(label)
        view.state(description, smallcap = false)
        view.onTap {
            onTap(ktx)
            onTapped()
        }
    }
}

enum class OneTimeByte {
    CLEANUP, UPDATED, OBSOLETE, DONATE, UPDATE_AVAILABLE
}

private var updateClickCounter = 0
private var updateNextLink = 0

fun createOneTimeBytes(
        ktx: AndroidKontext,
        i18n: I18n = ktx.di().instance(),
        repo: Repo = ktx.di().instance(),
        updateCoordinator: UpdateCoordinator = ktx.di().instance()
) = mapOf(
        OneTimeByte.CLEANUP to CleanupVB(ktx),
        OneTimeByte.UPDATED to SimpleByteVB(ktx,
                label = R.string.home_whats_new.res(),
                description = R.string.slot_updated_desc.res(),
                onTap = { ktx ->
                    val pages: Pages = ktx.di().instance()
                    modalManager.openModal()
                    ktx.ctx.startActivity(Intent(ktx.ctx, WebViewActivity::class.java).apply {
                        putExtra(WebViewActivity.EXTRA_URL, pages.updated().toExternalForm())
                    })
                }
        ),
        OneTimeByte.OBSOLETE to SimpleByteVB(ktx,
                label = R.string.home_update_required.res(),
                description = R.string.slot_obsolete_desc.res(),
                onTap = { ktx ->
                    val pages: Pages = ktx.di().instance()
                    openInBrowser(ktx.ctx, pages.download())
                }
        ),
        OneTimeByte.DONATE to SimpleByteVB(ktx,
                label = R.string.home_donate.res(),
                description = R.string.slot_donate_desc.res(),
                onTap = { ktx ->
                    val pages: Pages = ktx.di().instance()
                    openInBrowser(ktx.ctx, pages.donate())
                }
        ),
        OneTimeByte.UPDATE_AVAILABLE to SimpleByteVB(ktx,
            label = R.string.update_notification_title.res(),
            description = i18n.getString(R.string.update_notification_text, repo.content().newestVersionName).res(),
            shouldKeepAfterTap = true,
            onTap = {
                    if (updateClickCounter++ % 2 == 0) {
                            showSnack(R.string.update_starting)
                            updateCoordinator.start(repo.content().downloadLinks)
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            intent.data = Uri.parse(repo.content().downloadLinks[updateNextLink].toString())

                            ktx.ctx.startActivity(intent)

                            updateNextLink = updateNextLink++ % repo.content().downloadLinks.size
                        }
                }
        )
)


class ShareVB(
        val ktx: AndroidKontext,
        private val tunnelEvents: Tunnel = ktx.di().instance()
) : ByteVB() {
    override fun attach(view: ByteView) {
        view.run {
            icon(null)
            arrow(R.drawable.ic_share.res())
            label(R.string.home_share.res())
            state(R.string.home_share_state.res())
            onArrowTap { share() }
            onTap { share() }
        }
    }

    private fun share() {
        try {
            val shareIntent: Intent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, getMessage(ktx.ctx,
                        tunnelEvents.tunnelDropStart(), Format.counter(tunnelEvents.tunnelDropCount())))
                type = "text/plain"
            }
            ktx.ctx.startActivity(Intent.createChooser(shareIntent,
                    ktx.ctx.getText(R.string.slot_dropped_share_title)))
        } catch (e: Exception) {}
    }

    private fun getMessage(ctx: Context, timeStamp: Long, dropCount: String): String {
        var elapsed: Long = System.currentTimeMillis() - timeStamp
        elapsed /= 60000
        if (elapsed < 120) {
            return ctx.resources.getString(R.string.social_share_body_minute, dropCount, elapsed)
        }
        elapsed /= 60
        if (elapsed < 48) {
            return ctx.resources.getString(R.string.social_share_body_hour, dropCount, elapsed)
        }
        elapsed /= 24
        if (elapsed < 28) {
            return ctx.resources.getString(R.string.social_share_body_day, dropCount, elapsed)
        }
        elapsed /= 7
        return ctx.resources.getString(R.string.social_share_body_week, dropCount, elapsed)
    }

}
