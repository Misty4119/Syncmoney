package noietime.syncmoney.economy;

/** Receipts for both account rows committed by one LOCAL SQLite transfer transaction. */
record LocalTransferPersistenceReceipt(LocalPersistenceReceipt out, LocalPersistenceReceipt in) {
}
