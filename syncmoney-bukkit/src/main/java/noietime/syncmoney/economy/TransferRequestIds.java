package noietime.syncmoney.economy;

import java.util.UUID;

/** Keeps both accepted transfer legs linked without changing the LOCAL journal framing. */
final class TransferRequestIds {

    private static final String PREFIX = "transfer:";
    private static final String OUT_SUFFIX = ":out";
    private static final String IN_SUFFIX = ":in";

    private TransferRequestIds() { }

    static String newOperationId() {
        return PREFIX + UUID.randomUUID();
    }

    static String requestId(String operationId, EconomyEvent.EventType type) {
        return operationId + switch (type) {
            case TRANSFER_OUT -> OUT_SUFFIX;
            case TRANSFER_IN -> IN_SUFFIX;
            default -> throw new IllegalArgumentException("Not a transfer leg: " + type);
        };
    }

    static boolean isTransferLeg(EconomyEvent event) {
        return event != null && (event.type() == EconomyEvent.EventType.TRANSFER_OUT
                || event.type() == EconomyEvent.EventType.TRANSFER_IN)
                && operationId(event.requestId()) != null;
    }

    static String operationId(EconomyEvent event) {
        return event == null ? null : operationId(event.requestId());
    }

    static String operationId(String requestId) {
        if (requestId == null || !requestId.startsWith(PREFIX)) return null;
        if (requestId.endsWith(OUT_SUFFIX)) {
            return requestId.substring(0, requestId.length() - OUT_SUFFIX.length());
        }
        if (requestId.endsWith(IN_SUFFIX)) {
            return requestId.substring(0, requestId.length() - IN_SUFFIX.length());
        }
        return null;
    }

    static boolean arePair(EconomyEvent first, EconomyEvent second) {
        if (!isTransferLeg(first) || !isTransferLeg(second)) return false;
        if (!operationId(first).equals(operationId(second))) return false;
        return first.type() != second.type()
                && first.playerUuid() != null && second.playerUuid() != null
                && !first.playerUuid().equals(second.playerUuid())
                && first.delta().signum() != second.delta().signum()
                && first.delta().abs().compareTo(second.delta().abs()) == 0;
    }
}
