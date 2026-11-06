package io.reliablepush.shared.web;

import java.sql.SQLTransientConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * Database backpressure: when the connection pool has no free connection within
 * {@code spring.datasource.hikari.connection-timeout} (5 s), the request is answered {@code 503} with
 * {@code Retry-After: 5} instead of {@code 500}. The cause is logged as one WARN line without stack trace or exception
 * message (it is load, not a bug). Recognised in {@link ProblemHandler} (controllers), {@link ProblemErrorController}
 * (anything that escaped a servlet filter) and by filters that read the database themselves.
 */
public final class DatabaseUnavailable {

    public static final long RETRY_AFTER_SECONDS = 5;
    public static final String TITLE = "Service Unavailable";
    public static final String DETAIL = "The service is temporarily overloaded; retry later.";

    private static final Logger LOG = LogManager.getLogger(DatabaseUnavailable.class);

    private DatabaseUnavailable() {}

    /**
     * True if {@code failure} is, or is caused by, a failure to obtain a pooled connection: Spring's
     * {@link CannotGetJdbcConnectionException} (JdbcClient) or Hikari's {@link SQLTransientConnectionException} wrapped
     * in anything else (e.g. {@code CannotCreateTransactionException} from a {@code TransactionTemplate}, or
     * {@code DataAccessResourceFailureException}).
     */
    public static boolean isPoolTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CannotGetJdbcConnectionException || t instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    /** One WARN line naming the exception type only: no message (it could carry pool or host details), no trace. */
    public static void logWarning(Throwable failure) {
        LOG.warn("Database connection unavailable ({}); answered 503 with Retry-After {}",
                failure.getClass().getSimpleName(), RETRY_AFTER_SECONDS);
    }
}
