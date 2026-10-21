package com.globalfutservice.identity;

/** How a marketing choice was made. See V58. */
public enum ConsentSource {
    /** The box on the sign-up form, ticked or not. */
    SIGN_UP,
    /** The switch in Account settings. */
    ACCOUNT_SETTINGS,
    /** The unsubscribe link in a promotional email. */
    UNSUBSCRIBE_LINK
}
