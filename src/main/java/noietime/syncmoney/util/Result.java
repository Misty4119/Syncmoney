package noietime.syncmoney.util;

import noietime.syncmoney.exception.SyncmoneyException;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A result container type for representing success or failure operations.
 * Provides a functional approach to error handling without throwing exceptions.
 *
 * @param <T> The type of the successful value
 */
public final class Result<T> {

    private final T value;
    private final SyncmoneyException error;
    private final boolean success;

    private Result(T value, SyncmoneyException error, boolean success) {
        this.value = value;
        this.error = error;
        this.success = success;
    }

    /**
     * Creates a successful result with a value.
     *
     * @param value The success value
     * @param <T>   The type of the value
     * @return A successful Result instance
     */
    public static <T> Result<T> success(T value) {
        return new Result<>(value, null, true);
    }

    /**
     * Creates an error result with an exception.
     *
     * @param error The exception that caused the error
     * @param <T>   The type of the expected value
     * @return An error Result instance
     */
    public static <T> Result<T> error(SyncmoneyException error) {
        return new Result<>(null, error, false);
    }

    /**
     * Creates an error result with an error code and message.
     *
     * @param code    The error code
     * @param message The error message
     * @param <T>    The type of the expected value
     * @return An error Result instance
     */
    public static <T> Result<T> error(SyncmoneyException.ErrorCode code, String message) {
        return new Result<>(null, new SyncmoneyException(code, message), false);
    }

    /**
     * Creates an error result with an error code and message, wrapping a cause.
     *
     * @param code    The error code
     * @param message The error message
     * @param cause   The underlying cause
     * @param <T>    The type of the expected value
     * @return An error Result instance
     */
    public static <T> Result<T> error(SyncmoneyException.ErrorCode code, String message, Throwable cause) {
        return new Result<>(null, new SyncmoneyException(code, message, cause), false);
    }

    /**
     * Returns true if the operation was successful.
     *
     * @return true if success, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns true if the operation failed.
     *
     * @return true if failure, false otherwise
     */
    public boolean isError() {
        return !success;
    }

    /**
     * Gets the success value.
     *
     * @return The value if successful
     * @throws IllegalStateException if this is an error result
     */
    public T getValue() {
        if (!success) {
            throw new IllegalStateException("Cannot get value from error Result: " + error);
        }
        return value;
    }

    /**
     * Gets the error exception.
     *
     * @return The error if failed, null if successful
     */
    public SyncmoneyException getError() {
        return error;
    }

    /**
     * Gets the value if successful, otherwise returns the provided default value.
     *
     * @param defaultValue The default value to return on error
     * @return The success value or default value
     */
    public T orElse(T defaultValue) {
        return success ? value : defaultValue;
    }

    /**
     * Gets the value if successful, otherwise returns the result of the supplier.
     *
     * @param supplier The supplier to provide a default value
     * @return The success value or computed default value
     */
    public T orElseGet(java.util.function.Supplier<T> supplier) {
        return success ? value : supplier.get();
    }

    /**
     * Gets the value if successful, otherwise throws the provided exception.
     *
     * @param <X>      The type of exception to throw
     * @param supplier The supplier for the exception
     * @return The success value
     * @throws X if this is an error result
     */
    public <X extends Throwable> T orElseThrow(Supplier<X> supplier) throws X {
        if (!success) {
            throw supplier.get();
        }
        return value;
    }

    /**
     * Maps the success value to another type.
     *
     * @param mapper The function to map the value
     * @param <R>    The type of the mapped value
     * @return A new Result with the mapped value if successful, otherwise the same error
     */
    public <R> Result<R> map(Function<T, R> mapper) {
        if (success) {
            try {
                return new Result<>(mapper.apply(value), null, true);
            } catch (Throwable e) {
                return error(SyncmoneyException.ErrorCode.UNKNOWN_ERROR, "Mapping failed: " + e.getMessage(), e);
            }
        }
        return new Result<>(null, error, false);
    }

    /**
     * Maps the success value to another Result.
     *
     * @param mapper The function to map to another Result
     * @param <R>    The type of the mapped Result
     * @return The mapped Result
     */
    public <R> Result<R> flatMap(Function<T, Result<R>> mapper) {
        if (success) {
            try {
                return mapper.apply(value);
            } catch (Throwable e) {
                return error(SyncmoneyException.ErrorCode.UNKNOWN_ERROR, "FlatMap failed: " + e.getMessage(), e);
            }
        }
        return new Result<>(null, error, false);
    }

    /**
     * Executes the consumer if the result is successful.
     *
     * @param consumer The consumer to execute
     * @return This Result for method chaining
     */
    public Result<T> ifSuccess(Consumer<T> consumer) {
        if (success) {
            consumer.accept(value);
        }
        return this;
    }

    /**
     * Executes the consumer if the result is an error.
     *
     * @param consumer The consumer to execute
     * @return This Result for method chaining
     */
    public Result<T> ifError(Consumer<SyncmoneyException> consumer) {
        if (!success) {
            consumer.accept(error);
        }
        return this;
    }

    @Override
    public String toString() {
        if (success) {
            return "Result{success=true, value=" + value + "}";
        }
        return "Result{success=false, error=" + error + "}";
    }
}
