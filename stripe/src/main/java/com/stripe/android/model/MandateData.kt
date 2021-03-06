package com.stripe.android.model

internal class MandateData : StripeParamsModel {
    override fun toParamMap(): Map<String, Any> {
        return mapOf("customer_acceptance" to mapOf(
            "type" to "online",
            "online" to mapOf("infer_from_client" to true)
        ))
    }

    companion object {
        internal const val API_PARAM_MANDATE_DATA = "mandate_data"
    }
}
