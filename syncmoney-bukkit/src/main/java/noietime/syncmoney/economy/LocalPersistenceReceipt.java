package noietime.syncmoney.economy;

import java.math.BigDecimal;

/** Internal result from a committed LOCAL SQLite transaction. */
record LocalPersistenceReceipt(BigDecimal balance, long version) {
}
