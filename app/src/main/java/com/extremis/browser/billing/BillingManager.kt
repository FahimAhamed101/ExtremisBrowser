package com.extremis.browser.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.extremis.browser.utils.BrowserPreferences

/**
 * The single paid subscription: removes every ad.
 *
 * Play owns the truth. The entitlement is cached in [BrowserPreferences] so ads stay off offline
 * and on the first frame after a cold start, then re-checked against Play on every connection.
 * A lapsed subscription is cleared as soon as Play says there is nothing active — but only on a
 * successful answer, never on a network error, or a flaky connection would switch the ads back on
 * for someone who has paid.
 *
 * Nothing here throws when Play is missing (an emulator, a sideloaded build, a device without the
 * Play Store): [available] simply stays false and the UI hides the offer.
 */
object BillingManager {

    /** Product id configured in Play Console → Monetise → Subscriptions. */
    const val PRODUCT_ID = "ad_free_monthly"

    private const val TAG = "BillingManager"

    @Volatile
    var isAdFree: Boolean = false
        private set

    /** Price exactly as Play formats it for this user, e.g. "$5.00/month". */
    @Volatile
    var priceLabel: String? = null
        private set

    /** True once Play has answered, so the offer can be shown with a real price. */
    @Volatile
    var available: Boolean = false
        private set

    /** Invoked on the main thread whenever [isAdFree] changes. */
    @Volatile
    var onEntitlementChanged: (() -> Unit)? = null

    private var appContext: Context? = null
    private var client: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var connecting = false

    fun start(context: Context) {
        val application = context.applicationContext
        appContext = application

        // Cached value first, so the very first frame already has the right answer.
        isAdFree = BrowserPreferences.get(application).adFree

        if (client != null) {
            refresh()
            return
        }

        client = BillingClient.newBuilder(application)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()

        connect()
    }

    private fun connect() {
        val billing = client ?: return
        if (connecting) return
        connecting = true

        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing unavailable: ${result.responseCode}")
                    return
                }
                queryProduct()
                refresh()
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                // Play reconnects on the next call; nothing to do until then.
            }
        })
    }

    /** Loads the subscription so the UI can show the real price Play will charge. */
    private fun queryProduct() {
        val billing = client ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        billing.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Product lookup failed: ${result.responseCode}")
                return@queryProductDetailsAsync
            }
            val product = detailsResult.productDetailsList.firstOrNull() ?: return@queryProductDetailsAsync
            productDetails = product
            priceLabel = product.subscriptionOfferDetails
                ?.firstOrNull()
                ?.pricingPhases
                ?.pricingPhaseList
                // The last phase is the recurring one; earlier phases are intro or trial prices.
                ?.lastOrNull()
                ?.formattedPrice
            available = priceLabel != null
        }
    }

    /** Re-reads the active subscriptions. Safe to call at any time. */
    fun refresh() {
        val billing = client ?: return
        if (!billing.isReady) {
            connect()
            return
        }

        billing.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // Keep the cached entitlement: a failed lookup is not a cancellation.
                return@queryPurchasesAsync
            }
            val active = purchases.any { it.isActiveFor(PRODUCT_ID) }
            purchases.forEach { purchase ->
                if (purchase.isActiveFor(PRODUCT_ID)) acknowledgeIfNeeded(purchase)
            }
            applyEntitlement(active)
        }
    }

    /** Opens the Play purchase sheet. Returns false when there is nothing to offer. */
    fun subscribe(activity: Activity): Boolean {
        val billing = client
        val details = productDetails
        if (billing == null || details == null) {
            connect()
            return false
        }
        val offerToken = details.subscriptionOfferDetails
            ?.firstOrNull()
            ?.offerToken
            ?: return false

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()

        return billing.launchBillingFlow(activity, params).responseCode ==
            BillingClient.BillingResponseCode.OK
    }

    /** Opens the Play subscription management page for this app. */
    fun manageUri(): android.net.Uri = android.net.Uri.parse(
        "https://play.google.com/store/account/subscriptions" +
            "?sku=$PRODUCT_ID&package=${appContext?.packageName.orEmpty()}"
    )

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val active = purchases.orEmpty().any { it.isActiveFor(PRODUCT_ID) }
                purchases.orEmpty().forEach { purchase ->
                    if (purchase.isActiveFor(PRODUCT_ID)) acknowledgeIfNeeded(purchase)
                }
                if (active) applyEntitlement(true) else refresh()
            }
            // The user closed the sheet: nothing changes.
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> Log.i(TAG, "Purchase failed: ${result.responseCode}")
        }
    }

    /**
     * Play refunds and cancels a purchase that is not acknowledged within three days, so this has
     * to happen before the entitlement is trusted.
     */
    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val billing = client ?: return
        billing.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        ) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Acknowledge failed: ${result.responseCode}")
            }
        }
    }

    private fun applyEntitlement(active: Boolean) {
        val changed = isAdFree != active
        isAdFree = active
        appContext?.let { BrowserPreferences.get(it).adFree = active }
        if (changed) onEntitlementChanged?.invoke()
    }

    private fun Purchase.isActiveFor(productId: String): Boolean =
        purchaseState == Purchase.PurchaseState.PURCHASED && products.contains(productId)
}
