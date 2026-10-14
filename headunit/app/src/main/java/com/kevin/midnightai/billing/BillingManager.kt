package com.kevin.midnightai.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Play Billing for the one-time `unlock_byo` in-app product (CLAUDE.md sec 2,
 * 2026-07-16 rewrite: two tiers, no trial, no subscription, no broker).
 * Purchasing it doesn't touch any server - see [UnlockState] - it just reveals
 * the BYO-key field in the "AI & Plan" settings section (which is hidden
 * behind this unlock until purchased). There is no second product; the
 * monthly subscription was killed with the broker.
 *
 * Call [init] once at app start (alongside the other providers in
 * MainActivity). [launchPurchase] needs a foreground [Activity] (Play
 * Billing's own requirement) - call it from wherever the paywall/unlock
 * button lives.
 *
 * Requires the `unlock_byo` in-app product to exist in Play Console before
 * [productDetails] resolves to anything - until then, [launchPurchase] is a
 * no-op (there's nothing to buy yet). This is a Play Console setup step
 * Kevin has to do by hand; it can't be done from this repo.
 */
object BillingManager {
    private const val TAG = "BillingManager"
    private const val PRODUCT_UNLOCK_BYO = "unlock_byo"

    private var client: BillingClient? = null

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun init(context: Context) {
        val appContext = context.applicationContext
        _unlocked.value = UnlockState.isUnlocked(appContext)
        if (client != null) return
        // Wrapped: on a head unit with a broken/absent Play Store, BillingClient
        // setup must never throw into MainActivity.onCreate and take down the app.
        // The paywall's purchase button just no-ops if this never connects.
        try {
            val listener = PurchasesUpdatedListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    purchases.forEach { handlePurchase(appContext, it) }
                }
            }
            val c = BillingClient.newBuilder(appContext)
                .setListener(listener)
                .enablePendingPurchases()
                .build()
            client = c
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProductDetails(c)
                        restorePurchases(appContext, c)
                    } else {
                        Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "Billing service disconnected")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Billing init failed, purchases unavailable: ${e.message}")
        }
    }

    /**
     * Debug-only: simulate a successful `unlock_byo` purchase without Play
     * Billing, so the $10 unlock -> BYO-key journey can be exercised on an
     * emulator / sideloaded build where Play Billing (and the Play Console
     * product) isn't available. Gated to debug builds at the call site
     * (BuildConfig.DEBUG); never reachable in a release build.
     */
    fun debugGrantUnlock(context: Context) {
        UnlockState.markUnlocked(context.applicationContext)
        _unlocked.value = true
    }

    /** Launches the Play purchase sheet for the one-time unlock. No-op if product details haven't loaded yet. */
    fun launchPurchase(activity: Activity) {
        val details = _productDetails.value ?: run {
            Log.w(TAG, "launchPurchase called before product details loaded")
            return
        }
        val c = client ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        c.launchBillingFlow(activity, params)
    }

    private fun queryProductDetails(c: BillingClient) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_UNLOCK_BYO)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        c.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _productDetails.value = productDetailsList.firstOrNull()
            } else {
                Log.w(TAG, "queryProductDetails failed: ${result.debugMessage}")
            }
        }
    }

    /** Restores unlock state from Play's purchase records (e.g. a reinstall on the same account). */
    private fun restorePurchases(context: Context, c: BillingClient) {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { handlePurchase(context, it) }
            }
        }
    }

    private fun handlePurchase(context: Context, purchase: Purchase) {
        if (PRODUCT_UNLOCK_BYO !in purchase.products) return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        UnlockState.markUnlocked(context)
        _unlocked.value = true
        if (!purchase.isAcknowledged) {
            val c = client ?: return
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            c.acknowledgePurchase(ackParams) { ackResult ->
                if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "Acknowledge failed: ${ackResult.debugMessage}")
                }
            }
        }
    }
}
