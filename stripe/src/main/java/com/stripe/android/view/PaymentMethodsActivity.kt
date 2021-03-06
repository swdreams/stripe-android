package com.stripe.android.view

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.google.android.material.snackbar.Snackbar
import com.stripe.android.CustomerSession
import com.stripe.android.PaymentSession.Companion.TOKEN_PAYMENT_SESSION
import com.stripe.android.R
import com.stripe.android.exception.StripeException
import com.stripe.android.model.PaymentMethod
import com.stripe.android.view.i18n.TranslatorManager
import kotlinx.android.synthetic.main.activity_payment_methods.*

/**
 * An activity that allows a customer to select from their attached payment methods,
 * or add a new one via [AddPaymentMethodActivity].
 *
 * This Activity is typically started through [com.stripe.android.PaymentSession].
 * To directly start this activity, use [PaymentMethodsActivityStarter.startForResult].
 *
 * Use [PaymentMethodsActivityStarter.Result.fromIntent]
 * to retrieve the result of this activity from an intent in onActivityResult().
 */
class PaymentMethodsActivity : AppCompatActivity() {

    private lateinit var adapter: PaymentMethodsAdapter
    private var startedFromPaymentSession: Boolean = false
    private lateinit var customerSession: CustomerSession
    private lateinit var cardDisplayTextFactory: CardDisplayTextFactory

    private lateinit var viewModel: PaymentMethodsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_methods)

        viewModel = ViewModelProviders.of(this).get(PaymentMethodsViewModel::class.java)

        val args = PaymentMethodsActivityStarter.Args.create(intent)
        startedFromPaymentSession = args.isPaymentSessionActive
        cardDisplayTextFactory = CardDisplayTextFactory.create(this)
        customerSession = CustomerSession.getInstance()

        setupRecyclerView(args, savedInstanceState)

        setSupportActionBar(payment_methods_toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        viewModel.paymentMethods.observe(this, Observer {
            when (it.status) {
                PaymentMethodsViewModel.Result.Status.SUCCESS -> {
                    val paymentMethods = it.data as List<PaymentMethod>
                    adapter.setPaymentMethods(paymentMethods)
                }
                PaymentMethodsViewModel.Result.Status.ERROR -> {
                    val exception = it.data as StripeException
                    val displayedError = TranslatorManager.getErrorMessageTranslator()
                        .translate(exception.statusCode, exception.message, exception.stripeError)
                    showError(displayedError)
                }
            }
            setCommunicatingProgress(false)
        })

        fetchCustomerPaymentMethods()

        // This prevents the first click from being eaten by the focus.
        payment_methods_recycler.requestFocusFromTouch()
    }

    private fun setupRecyclerView(
        args: PaymentMethodsActivityStarter.Args,
        savedInstanceState: Bundle?
    ) {
        val initiallySelectedPaymentMethodId =
            savedInstanceState?.getString(STATE_SELECTED_PAYMENT_METHOD_ID)
                ?: args.initialPaymentMethodId

        adapter = PaymentMethodsAdapter(
            initiallySelectedPaymentMethodId,
            args,
            args.paymentMethodTypes
        )

        adapter.listener = object : PaymentMethodsAdapter.Listener {
            override fun onClick(paymentMethod: PaymentMethod) {
                payment_methods_recycler.tappedPaymentMethod = paymentMethod
            }
        }

        payment_methods_recycler.adapter = adapter
        payment_methods_recycler.listener = object : PaymentMethodsRecyclerView.Listener {
            override fun onPaymentMethodSelected(paymentMethod: PaymentMethod) {
                setSelectionAndFinish(paymentMethod)
            }
        }

        payment_methods_recycler.attachItemTouchHelper(
            PaymentMethodSwipeCallback(this, adapter,
                SwipeToDeleteCallbackListener(this, adapter, cardDisplayTextFactory,
                    customerSession))
        )
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AddPaymentMethodActivityStarter.REQUEST_CODE &&
            resultCode == Activity.RESULT_OK) {
            onPaymentMethodCreated(data)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        setSelectionAndFinish(adapter.selectedPaymentMethod)
        return true
    }

    private fun onPaymentMethodCreated(data: Intent?) {
        initLoggingTokens()

        data?.let {
            val result =
                AddPaymentMethodActivityStarter.Result.fromIntent(data)
            result?.paymentMethod?.let { onAddedPaymentMethod(it) }
        } ?: fetchCustomerPaymentMethods()
    }

    private fun onAddedPaymentMethod(paymentMethod: PaymentMethod) {
        val type = PaymentMethod.Type.lookup(paymentMethod.type)
        if (type?.isReusable == true) {
            // Refresh the list of Payment Methods with the new reusable Payment Method.
            fetchCustomerPaymentMethods()
            showSnackbar(paymentMethod, R.string.added)
        } else {
            // If the added Payment Method is not reusable, it also can't be attached to a
            // customer, so immediately return to the launching host with the new
            // Payment Method.
            finishWithPaymentMethod(paymentMethod)
        }
    }

    @JvmSynthetic
    internal fun showSnackbar(paymentMethod: PaymentMethod, @StringRes stringRes: Int) {
        val snackbarText = paymentMethod.card?.let { paymentMethodId ->
            getString(stringRes, cardDisplayTextFactory.createUnstyled(paymentMethodId))
        }

        if (snackbarText != null) {
            Snackbar.make(
                payment_methods_coordinator,
                snackbarText,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    override fun onBackPressed() {
        setSelectionAndFinish(adapter.selectedPaymentMethod)
    }

    private fun fetchCustomerPaymentMethods() {
        setCommunicatingProgress(true)
        viewModel.loadPaymentMethods()
    }

    private fun initLoggingTokens() {
        if (startedFromPaymentSession) {
            customerSession.addProductUsageTokenIfValid(TOKEN_PAYMENT_SESSION)
        }
        customerSession.addProductUsageTokenIfValid(TOKEN_PAYMENT_METHODS_ACTIVITY)
    }

    private fun cancelAndFinish() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun setCommunicatingProgress(communicating: Boolean) {
        payment_methods_progress_bar.visibility = if (communicating) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    @JvmSynthetic
    internal fun setSelectionAndFinish(paymentMethod: PaymentMethod?) {
        if (paymentMethod == null) {
            cancelAndFinish()
        } else {
            finishWithPaymentMethod(paymentMethod)
        }
    }

    private fun finishWithPaymentMethod(paymentMethod: PaymentMethod) {
        setResult(Activity.RESULT_OK, Intent()
            .putExtras(PaymentMethodsActivityStarter.Result(paymentMethod).toBundle())
        )
        finish()
    }

    private fun showError(error: String) {
        AlertDialog.Builder(this, R.style.AlertDialogStyle)
            .setMessage(error)
            .setCancelable(true)
            .setPositiveButton(android.R.string.ok) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            .create()
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_PAYMENT_METHOD_ID, adapter.selectedPaymentMethod?.id)
    }

    companion object {
        private const val STATE_SELECTED_PAYMENT_METHOD_ID = "state_selected_payment_method_id"
        internal const val TOKEN_PAYMENT_METHODS_ACTIVITY: String = "PaymentMethodsActivity"
    }
}
