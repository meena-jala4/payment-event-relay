package nz.paymentrelay.payment.api;

public final class AccountNumbers {

    /** Bank(2)-Branch(4)-Account(7)-Suffix(2 or 3). Format check only; no checksum (see README assumptions). */
    public static final String NZ_FORMAT = "^\\d{2}-\\d{4}-\\d{7}-\\d{2,3}$";
    public static final String NZ_FORMAT_MESSAGE = "must be an NZ account number like 12-3456-7890123-00";

    private AccountNumbers() {
    }
}
