package com.darshan.recon.matching;

import com.darshan.recon.matching.engine.ReferenceNormaliser;
import java.sql.Date;
import org.springframework.jdbc.core.PreparedStatementSetter;

// One row per normalised reference: the statement side keeps only the first copy of a
// repeated reference and carries the line numbers of every copy, so duplicates are counted
// in SQL and the Java rules never need to remember earlier rows.
final class CandidatePairQuery {

    // hashtext is PostgreSQL's internal text hash; masking the sign bit avoids the
    // abs(Integer.MIN_VALUE) overflow and keeps the partition index in 0..count-1.
    private static final String PARTITION_FILTER = "(hashtext(reference_key) & 2147483647) % ? = ?";

    static final String PAIRING_SQL = """
            with statement_keys as (
                select id, line_number, bank_reference, amount, value_date, description,
                       {statementKey} as reference_key
                from statement_line
                where statement_file_id = ?
            ), statement_side as (
                select id, line_number, bank_reference, amount, value_date, description, reference_key,
                       row_number() over (partition by reference_key order by line_number) as copy_number,
                       array_to_string(array_agg(line_number) over (partition by reference_key order by line_number
                           rows between unbounded preceding and unbounded following), ',') as copy_line_numbers
                from statement_keys
                where {partitionFilter}
            ), ledger_side as (
                select id, txn_reference, amount, value_date, description, reference_key
                from (select id, txn_reference, amount, value_date, description,
                             {ledgerKey} as reference_key
                      from ledger_transaction
                      where account_number = ? and value_date between ? and ?) ledger_keys
                where {partitionFilter}
            )
            select l.id as ledger_id, l.txn_reference, l.amount as ledger_amount,
                   l.value_date as ledger_value_date, l.description as ledger_description,
                   s.id as statement_line_id, s.line_number, s.bank_reference, s.amount as bank_amount,
                   s.value_date as bank_value_date, s.description as bank_description, s.copy_line_numbers
            from (select * from statement_side where copy_number = 1) s
            full outer join ledger_side l on l.reference_key = s.reference_key
            order by coalesce(s.reference_key, l.reference_key), l.id
            """
            .replace("{statementKey}", ReferenceNormaliser.sql("bank_reference"))
            .replace("{ledgerKey}", ReferenceNormaliser.sql("txn_reference"))
            .replace("{partitionFilter}", PARTITION_FILTER);

    // The same pairing without partitions, reduced to its size: every row of it produced
    // exactly one outcome, so candidates minus breaks is the number of matches.
    static final String COUNT_SQL = """
            with statement_keys as (
                select distinct {statementKey} as reference_key
                from statement_line
                where statement_file_id = ?
            ), ledger_side as (
                select {ledgerKey} as reference_key
                from ledger_transaction
                where account_number = ? and value_date between ? and ?
            )
            select count(*)
            from statement_keys s
            full outer join ledger_side l on l.reference_key = s.reference_key
            """
            .replace("{statementKey}", ReferenceNormaliser.sql("bank_reference"))
            .replace("{ledgerKey}", ReferenceNormaliser.sql("txn_reference"));

    private CandidatePairQuery() {
    }

    static PreparedStatementSetter pairingParameters(long statementFileId, String accountNumber, ReconWindow window,
                                                     int partitionCount, int partitionIndex) {
        return ps -> {
            ps.setLong(1, statementFileId);
            ps.setInt(2, partitionCount);
            ps.setInt(3, partitionIndex);
            ps.setString(4, accountNumber);
            ps.setDate(5, Date.valueOf(window.from()));
            ps.setDate(6, Date.valueOf(window.to()));
            ps.setInt(7, partitionCount);
            ps.setInt(8, partitionIndex);
        };
    }

    static Object[] countParameters(long statementFileId, String accountNumber, ReconWindow window) {
        return new Object[] {statementFileId, accountNumber, window.from(), window.to()};
    }
}
