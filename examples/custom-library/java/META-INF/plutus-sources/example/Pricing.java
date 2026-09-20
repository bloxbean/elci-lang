package example;

import java.math.BigInteger;
import org.julclang.stdlib.annotation.OnchainLibrary;

@OnchainLibrary
public class Pricing {
    public record Quote(BigInteger amount, boolean approved) {}

    public static Quote quote(BigInteger amount) {
        return new Quote(amount, true);
    }

    public static BigInteger total(Quote quote) {
        return quote.amount();
    }
}
