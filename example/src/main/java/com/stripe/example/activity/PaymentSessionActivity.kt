package com.stripe.example.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.stripe.android.CustomerSession
import com.stripe.android.PayWithGoogleUtils.getPriceString
import com.stripe.android.PaymentSession
import com.stripe.android.PaymentSessionConfig
import com.stripe.android.PaymentSessionData
import com.stripe.android.StripeError
import com.stripe.android.model.Address
import com.stripe.android.model.Customer
import com.stripe.android.model.PaymentMethod
import com.stripe.android.model.ShippingInformation
import com.stripe.android.model.ShippingMethod
import com.stripe.android.view.PaymentFlowExtras.EVENT_SHIPPING_INFO_PROCESSED
import com.stripe.android.view.PaymentFlowExtras.EVENT_SHIPPING_INFO_SUBMITTED
import com.stripe.android.view.PaymentFlowExtras.EXTRA_DEFAULT_SHIPPING_METHOD
import com.stripe.android.view.PaymentFlowExtras.EXTRA_IS_SHIPPING_INFO_VALID
import com.stripe.android.view.PaymentFlowExtras.EXTRA_SHIPPING_INFO_DATA
import com.stripe.android.view.PaymentFlowExtras.EXTRA_VALID_SHIPPING_METHODS
import com.stripe.android.view.ShippingInfoWidget
import com.stripe.example.R
import com.stripe.example.controller.ErrorDialogHandler
import com.stripe.example.service.ExampleEphemeralKeyProvider
import kotlinx.android.synthetic.main.activity_payment_session.*
import java.util.ArrayList
import java.util.Currency
import java.util.Locale

/**
 * An example activity that handles working with a [PaymentSession], allowing you to collect
 * information needed to request payment for the current customer.
 */
class PaymentSessionActivity : AppCompatActivity() {

    private lateinit var broadcastReceiver: BroadcastReceiver
    private lateinit var errorDialogHandler: ErrorDialogHandler
    private lateinit var paymentSession: PaymentSession

    private var paymentSessionData: PaymentSessionData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_session)

        progress_bar.visibility = View.VISIBLE
        errorDialogHandler = ErrorDialogHandler(this)

        // CustomerSession only needs to be initialized once per app.
        val customerSession = createCustomerSession()
        paymentSession = createPaymentSession(customerSession)

        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val shippingInformation = intent
                    .getParcelableExtra<ShippingInformation>(EXTRA_SHIPPING_INFO_DATA)
                val shippingInfoProcessedIntent = Intent(EVENT_SHIPPING_INFO_PROCESSED)
                if (!isValidShippingInfo(shippingInformation)) {
                    shippingInfoProcessedIntent.putExtra(EXTRA_IS_SHIPPING_INFO_VALID, false)
                } else {
                    val shippingMethods = createSampleShippingMethods()
                    shippingInfoProcessedIntent
                        .putExtra(EXTRA_IS_SHIPPING_INFO_VALID, true)
                        .putParcelableArrayListExtra(EXTRA_VALID_SHIPPING_METHODS, shippingMethods)
                        .putExtra(EXTRA_DEFAULT_SHIPPING_METHOD, shippingMethods.last())
                }
                localBroadcastManager.sendBroadcast(shippingInfoProcessedIntent)
            }

            private fun isValidShippingInfo(shippingInfo: ShippingInformation?): Boolean {
                return shippingInfo?.address?.country == Locale.US.country
            }
        }
        localBroadcastManager.registerReceiver(broadcastReceiver,
            IntentFilter(EVENT_SHIPPING_INFO_SUBMITTED))
        btn_select_payment_method.setOnClickListener {
            paymentSession.presentPaymentMethodSelection(true)
        }
        btn_start_payment_flow.setOnClickListener {
            paymentSession.presentShippingFlow()
        }
    }

    private fun createCustomerSession(): CustomerSession {
        CustomerSession.initCustomerSession(
            this,
            ExampleEphemeralKeyProvider(),
            false
        )
        return CustomerSession.getInstance()
    }

    private fun createPaymentSession(customerSession: CustomerSession): PaymentSession {
        val paymentSession = PaymentSession(this)
        val paymentSessionInitialized = paymentSession.init(
            PaymentSessionListenerImpl(this, customerSession),
            PaymentSessionConfig.Builder()
                .setAddPaymentMethodFooter(R.layout.add_payment_method_footer)
                .setPrepopulatedShippingInfo(EXAMPLE_SHIPPING_INFO)
                .setHiddenShippingInfoFields(
                    ShippingInfoWidget.CustomizableShippingField.PHONE_FIELD,
                    ShippingInfoWidget.CustomizableShippingField.CITY_FIELD
                )

                // Optionally specify the `PaymentMethod.Type` values to use.
                // Defaults to `PaymentMethod.Type.Card`
                .setPaymentMethodTypes(listOf(PaymentMethod.Type.Card))
                .build())
        if (paymentSessionInitialized) {
            paymentSession.setCartTotal(2000L)
        }

        return paymentSession
    }

    private fun formatStringResults(data: PaymentSessionData): String {
        val currency = Currency.getInstance("USD")
        val stringBuilder = StringBuilder()

        data.paymentMethod?.card?.let { card ->
            stringBuilder
                .append("Payment Info:\n${card.brand} ending in ${card.last4}")
                .append(if (data.isPaymentReadyToCharge) " IS " else " IS NOT ready to charge.\n\n")
        }
        data.shippingInformation?.let { shippingInformation ->
            stringBuilder
                .append("Shipping Info: \n${shippingInformation}\n\n")
        }
        data.shippingMethod?.let { shippingMethod ->
            stringBuilder.append("Shipping Method: \n${shippingMethod}\n")
            if (data.shippingTotal > 0) {
                stringBuilder.append("Shipping total: ")
                    .append(getPriceString(data.shippingTotal, currency))
            }
        }

        return stringBuilder.toString()
    }

    private fun createSampleShippingMethods(): ArrayList<ShippingMethod> {
        return arrayListOf(
            ShippingMethod("UPS Ground", "ups-ground",
                0, "USD", "Arrives in 3-5 days"),
            ShippingMethod("FedEx", "fedex",
                599, "USD", "Arrives tomorrow")
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        paymentSession.handlePaymentData(requestCode, resultCode, data ?: Intent())
    }

    override fun onDestroy() {
        super.onDestroy()
        paymentSession.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
    }

    private fun onPaymentSessionDataChanged(
        customerSession: CustomerSession,
        data: PaymentSessionData
    ) {
        paymentSessionData = data
        progress_bar.visibility = View.VISIBLE
        customerSession.retrieveCurrentCustomer(
            PaymentSessionChangeCustomerRetrievalListener(this)
        )
    }

    private fun onCustomerRetrieved() {
        progress_bar.visibility = View.INVISIBLE
        btn_select_payment_method.isEnabled = true
        btn_start_payment_flow.isEnabled = true

        paymentSessionData?.let { paymentSessionData ->
            tv_payment_session_data_title.visibility = View.VISIBLE
            tv_payment_session_data.text = formatStringResults(paymentSessionData)
        }
    }

    private class PaymentSessionListenerImpl internal constructor(
        activity: PaymentSessionActivity,
        private val customerSession: CustomerSession
    ) : PaymentSession.ActivityPaymentSessionListener<PaymentSessionActivity>(activity) {

        override fun onCommunicatingStateChanged(isCommunicating: Boolean) {
            listenerActivity?.progress_bar?.visibility = if (isCommunicating) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
        }

        override fun onError(errorCode: Int, errorMessage: String) {
            listenerActivity?.errorDialogHandler?.show(errorMessage)
        }

        override fun onPaymentSessionDataChanged(data: PaymentSessionData) {
            listenerActivity?.onPaymentSessionDataChanged(customerSession, data)
        }
    }

    private class PaymentSessionChangeCustomerRetrievalListener internal constructor(
        activity: PaymentSessionActivity
    ) : CustomerSession.ActivityCustomerRetrievalListener<PaymentSessionActivity>(activity) {

        override fun onCustomerRetrieved(customer: Customer) {
            activity?.onCustomerRetrieved()
        }

        override fun onError(httpCode: Int, errorMessage: String, stripeError: StripeError?) {
            activity?.progress_bar?.visibility = View.INVISIBLE
        }
    }

    companion object {
        private val EXAMPLE_SHIPPING_INFO = ShippingInformation(
            Address.Builder()
                .setCity("San Francisco")
                .setCountry("US")
                .setLine1("123 Market St")
                .setLine2("#345")
                .setPostalCode("94107")
                .setState("CA")
                .build(),
            "Fake Name",
            "(555) 555-5555"
        )
    }
}
