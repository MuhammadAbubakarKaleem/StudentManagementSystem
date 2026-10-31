package com.ase.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.ase.model.Student;

public class FeeService {
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path VOUCHER_FILE = Path.of("fee_vouchers.txt");
    private static final Path PAYMENT_FILE = Path.of("fee_payments.txt");
    private static final String VOUCHER_HEADER = "voucher_id,student_id,amount,issued_at";
    private static final String PAYMENT_HEADER = "receipt_id,voucher_id,amount,paid_at";

    private final List<Student> students;
    private final List<Voucher> vouchers = new ArrayList<>();
    private int nextVoucherNumber = 1;
    private int nextReceiptNumber = 1;

    public FeeService(List<Student> students) {
        this.students = Objects.requireNonNull(students, "Students cannot be null.");
        loadSavedFees();
    }

    public Voucher generateVoucher(String studentId, BigDecimal amount) {
        Student student = findStudentById(studentId);
        if (student == null) {
            throw new IllegalArgumentException("Student not found.");
        }

        BigDecimal validatedAmount = validateAmount(amount);
        String voucherId = String.format("FV-%04d", nextVoucherNumber);
        Voucher voucher = new Voucher(voucherId, student.id, validatedAmount, LocalDateTime.now());
        vouchers.add(voucher);
        try {
            saveVouchers();
        } catch (IllegalStateException e) {
            vouchers.remove(voucher);
            throw e;
        }
        nextVoucherNumber++;
        return voucher;
    }

    public Payment recordPayment(String voucherId, BigDecimal amount) {
        Voucher voucher = findVoucherById(voucherId);
        if (voucher == null) {
            throw new IllegalArgumentException("Voucher not found.");
        }

        BigDecimal validatedAmount = validateAmount(amount);
        if (validatedAmount.compareTo(voucher.getBalance()) > 0) {
            throw new IllegalArgumentException("Payment cannot exceed the voucher balance of "
                    + voucher.getBalance().toPlainString() + ".");
        }

        Payment payment = new Payment(String.format("FR-%04d", nextReceiptNumber),
                validatedAmount, LocalDateTime.now());
        voucher.addPayment(payment);
        try {
            savePayments();
        } catch (IllegalStateException e) {
            voucher.removePayment(payment);
            throw e;
        }
        nextReceiptNumber++;
        return payment;
    }

    public Student findStudentById(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            return null;
        }
        for (Student student : students) {
            if (student != null && student.id != null
                    && student.id.equalsIgnoreCase(studentId.trim())) {
                return student;
            }
        }
        return null;
    }

    public List<Voucher> getFeeStatement(String studentId) {
        Student student = findStudentById(studentId);
        if (student == null) {
            throw new IllegalArgumentException("Student not found.");
        }

        List<Voucher> statement = new ArrayList<>();
        for (Voucher voucher : vouchers) {
            if (voucher.getStudentId().equalsIgnoreCase(student.id)) {
                statement.add(voucher);
            }
        }
        return Collections.unmodifiableList(statement);
    }

    public List<Defaulter> getDefaulters() {
        List<Defaulter> defaulters = new ArrayList<>();
        for (Student student : students) {
            if (student == null || student.id == null) {
                continue;
            }
            BigDecimal outstanding = BigDecimal.ZERO.setScale(2);
            int outstandingVoucherCount = 0;
            for (Voucher voucher : vouchers) {
                if (voucher.getStudentId().equalsIgnoreCase(student.id)
                        && voucher.getBalance().signum() > 0) {
                    outstanding = outstanding.add(voucher.getBalance());
                    outstandingVoucherCount++;
                }
            }
            if (outstanding.signum() > 0) {
                defaulters.add(new Defaulter(student.id, student.n, outstanding, outstandingVoucherCount));
            }
        }
        return Collections.unmodifiableList(defaulters);
    }

    private Voucher findVoucherById(String voucherId) {
        if (voucherId == null || voucherId.trim().isEmpty()) {
            return null;
        }
        for (Voucher voucher : vouchers) {
            if (voucher.getId().equalsIgnoreCase(voucherId.trim())) {
                return voucher;
            }
        }
        return null;
    }

    private static BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount can have no more than two decimal places.");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount can have no more than two decimal places.");
        }
    }

    private void loadSavedFees() {
        loadVouchers();
        loadPayments();
    }

    private void loadVouchers() {
        if (Files.notExists(VOUCHER_FILE)) {
            return;
        }

        int lineNumber = 0;
        try {
            for (String line : Files.readAllLines(VOUCHER_FILE, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (lineNumber == 1 && line.equals(VOUCHER_HEADER)) {
                    continue;
                }
                List<String> fields = parseCsvLine(line);
                if (fields.size() != 4) {
                    throw new IllegalArgumentException("Expected 4 CSV fields.");
                }

                String voucherId = requireText(fields.get(0), "voucher ID");
                String studentId = requireText(fields.get(1), "student ID");
                int voucherNumber = parseIdentifierNumber(voucherId, "FV-");
                if (findVoucherById(voucherId) != null) {
                    throw new IllegalArgumentException("Duplicate voucher ID: " + voucherId);
                }

                BigDecimal amount = parseStoredAmount(fields.get(2));
                LocalDateTime issuedAt = LocalDateTime.parse(fields.get(3), DATE_FORMAT);
                vouchers.add(new Voucher(voucherId, studentId, amount, issuedAt));
                nextVoucherNumber = Math.max(nextVoucherNumber, voucherNumber + 1);
            }
        } catch (IOException | RuntimeException e) {
            throw persistenceLoadError(VOUCHER_FILE, lineNumber, e);
        }
    }

    private void loadPayments() {
        if (Files.notExists(PAYMENT_FILE)) {
            return;
        }

        Set<String> receiptIds = new HashSet<>();
        int lineNumber = 0;
        try {
            for (String line : Files.readAllLines(PAYMENT_FILE, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (lineNumber == 1 && line.equals(PAYMENT_HEADER)) {
                    continue;
                }
                List<String> fields = parseCsvLine(line);
                if (fields.size() != 4) {
                    throw new IllegalArgumentException("Expected 4 CSV fields.");
                }

                String receiptId = requireText(fields.get(0), "receipt ID");
                String voucherId = requireText(fields.get(1), "voucher ID");
                int receiptNumber = parseIdentifierNumber(receiptId, "FR-");
                if (!receiptIds.add(receiptId.toUpperCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate receipt ID: " + receiptId);
                }

                Voucher voucher = findVoucherById(voucherId);
                if (voucher == null) {
                    throw new IllegalArgumentException(
                            "Payment refers to unknown voucher ID: " + voucherId);
                }
                BigDecimal amount = parseStoredAmount(fields.get(2));
                if (amount.compareTo(voucher.getBalance()) > 0) {
                    throw new IllegalArgumentException(
                            "Payments exceed the balance for voucher ID: " + voucherId);
                }
                LocalDateTime paidAt = LocalDateTime.parse(fields.get(3), DATE_FORMAT);
                voucher.addPayment(new Payment(receiptId, amount, paidAt));
                nextReceiptNumber = Math.max(nextReceiptNumber, receiptNumber + 1);
            }
        } catch (IOException | RuntimeException e) {
            throw persistenceLoadError(PAYMENT_FILE, lineNumber, e);
        }
    }

    private void saveVouchers() {
        createCsvIfMissing(PAYMENT_FILE, paymentCsvLines());
        writeCsv(VOUCHER_FILE, voucherCsvLines());
    }

    private void savePayments() {
        createCsvIfMissing(VOUCHER_FILE, voucherCsvLines());
        writeCsv(PAYMENT_FILE, paymentCsvLines());
    }

    private List<String> voucherCsvLines() {
        List<String> lines = new ArrayList<>();
        lines.add(VOUCHER_HEADER);
        for (Voucher voucher : vouchers) {
            lines.add(csvRow(voucher.id, voucher.studentId, voucher.amount.toPlainString(),
                    voucher.getIssuedAt()));
        }
        return lines;
    }

    private List<String> paymentCsvLines() {
        List<String> lines = new ArrayList<>();
        lines.add(PAYMENT_HEADER);
        for (Voucher voucher : vouchers) {
            for (Payment payment : voucher.payments) {
                lines.add(csvRow(payment.receiptId, voucher.id, payment.amount.toPlainString(),
                        payment.getPaidAt()));
            }
        }
        return lines;
    }

    private static void createCsvIfMissing(Path file, List<String> lines) {
        try {
            Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // Another save may have created the file already.
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create fee data file " + file + ".", e);
        }
    }

    private static void writeCsv(Path file, List<String> lines) {
        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile(file.toAbsolutePath().getParent(),
                    file.getFileName().toString(), ".tmp");
            Files.write(temporaryFile, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save fee data to " + file + ".", e);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Unable to clean up temporary fee data file " + temporaryFile + ".", e);
                }
            }
        }
    }

    private static String csvRow(String... fields) {
        List<String> escapedFields = new ArrayList<>(fields.length);
        for (String field : fields) {
            escapedFields.add("\"" + field.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escapedFields);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldClosed = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (quoted) {
                if (character == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        fieldClosed = true;
                    }
                } else {
                    field.append(character);
                }
            } else if (character == ',') {
                fields.add(field.toString());
                field.setLength(0);
                fieldClosed = false;
            } else if (character == '"' && field.length() == 0 && !fieldClosed) {
                quoted = true;
            } else if (fieldClosed) {
                throw new IllegalArgumentException("Unexpected characters after a quoted field.");
            } else if (character == '"') {
                throw new IllegalArgumentException("Unexpected quote in an unquoted field.");
            } else {
                field.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted field.");
        }
        fields.add(field.toString());
        return fields;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty.");
        }
        return value;
    }

    private static BigDecimal parseStoredAmount(String value) {
        try {
            return validateAmount(new BigDecimal(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid stored amount: " + value, e);
        }
    }

    private static int parseIdentifierNumber(String identifier, String prefix) {
        if (!identifier.startsWith(prefix) || identifier.length() == prefix.length()) {
            throw new IllegalArgumentException("Invalid ID: " + identifier);
        }
        try {
            int number = Integer.parseInt(identifier.substring(prefix.length()));
            if (number <= 0) {
                throw new IllegalArgumentException("Invalid ID: " + identifier);
            }
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid ID: " + identifier, e);
        }
    }

    private static IllegalStateException persistenceLoadError(Path file, int lineNumber,
            Exception cause) {
        return new IllegalStateException("Unable to load fee data from " + file
                + (lineNumber > 0 ? " at line " + lineNumber : "") + ".", cause);
    }

    public static final class Voucher {
        private final String id;
        private final String studentId;
        private final BigDecimal amount;
        private final LocalDateTime issuedAt;
        private final List<Payment> payments = new ArrayList<>();

        private Voucher(String id, String studentId, BigDecimal amount, LocalDateTime issuedAt) {
            this.id = id;
            this.studentId = studentId;
            this.amount = amount;
            this.issuedAt = issuedAt;
        }

        public String getId() {
            return id;
        }

        public String getStudentId() {
            return studentId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getIssuedAt() {
            return issuedAt.format(DATE_FORMAT);
        }

        public BigDecimal getPaidAmount() {
            BigDecimal paidAmount = BigDecimal.ZERO.setScale(2);
            for (Payment payment : payments) {
                paidAmount = paidAmount.add(payment.getAmount());
            }
            return paidAmount;
        }

        public BigDecimal getBalance() {
            return amount.subtract(getPaidAmount());
        }

        public List<Payment> getPayments() {
            return Collections.unmodifiableList(payments);
        }

        private void addPayment(Payment payment) {
            payments.add(payment);
        }

        private void removePayment(Payment payment) {
            payments.remove(payment);
        }
    }

    public static final class Payment {
        private final String receiptId;
        private final BigDecimal amount;
        private final LocalDateTime paidAt;

        private Payment(String receiptId, BigDecimal amount, LocalDateTime paidAt) {
            this.receiptId = receiptId;
            this.amount = amount;
            this.paidAt = paidAt;
        }

        public String getReceiptId() {
            return receiptId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getPaidAt() {
            return paidAt.format(DATE_FORMAT);
        }
    }

    public static final class Defaulter {
        private final String studentId;
        private final String studentName;
        private final BigDecimal outstandingAmount;
        private final int outstandingVoucherCount;

        private Defaulter(String studentId, String studentName, BigDecimal outstandingAmount,
                int outstandingVoucherCount) {
            this.studentId = studentId;
            this.studentName = studentName;
            this.outstandingAmount = outstandingAmount;
            this.outstandingVoucherCount = outstandingVoucherCount;
        }

        public String getStudentId() {
            return studentId;
        }

        public String getStudentName() {
            return studentName;
        }

        public BigDecimal getOutstandingAmount() {
            return outstandingAmount;
        }

        public int getOutstandingVoucherCount() {
            return outstandingVoucherCount;
        }
    }
}
