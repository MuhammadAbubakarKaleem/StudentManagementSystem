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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
    private static final String VOUCHER_HEADER =
            "voucher_id,student_id,semester,tuition_fee,lab_fee,other_charges,discount,amount,due_date,issued_at";
    private static final String LEGACY_VOUCHER_HEADER = "voucher_id,student_id,amount,issued_at";
    private static final String PAYMENT_HEADER = "receipt_id,voucher_id,amount,method,paid_at";
    private static final String LEGACY_PAYMENT_HEADER = "receipt_id,voucher_id,amount,paid_at";
    private static final BigDecimal DAILY_LATE_FEE = new BigDecimal("100.00");
    private static final BigDecimal LATE_FEE_CAP_RATE = new BigDecimal("0.10");
    private static final BigDecimal GPA_TIER_ONE = new BigDecimal("3.7");
    private static final BigDecimal GPA_TIER_TWO = new BigDecimal("3.5");
    private static final BigDecimal GPA_TIER_THREE = new BigDecimal("3.3");
    private static final BigDecimal DISCOUNT_TIER_ONE = new BigDecimal("0.75");
    private static final BigDecimal DISCOUNT_TIER_TWO = new BigDecimal("0.50");
    private static final BigDecimal DISCOUNT_TIER_THREE = new BigDecimal("0.25");

    private final List<Student> students;
    private final List<Voucher> vouchers = new ArrayList<>();
    private int nextVoucherNumber = 1;
    private int nextReceiptNumber = 1;

    public FeeService(List<Student> students) {
        this.students = Objects.requireNonNull(students, "Students cannot be null.");
        loadSavedFees();
    }

    public Voucher generateVoucher(String studentId, String semester, BigDecimal tuitionFee,
            BigDecimal labFee, BigDecimal otherCharges, LocalDate dueDate) {
        Student student = findStudentById(studentId);
        if (student == null) {
            throw new IllegalArgumentException("Student not found.");
        }

        String validatedSemester = validateSemester(semester);
        for (Voucher existingVoucher : vouchers) {
            if (existingVoucher.getStudentId().equalsIgnoreCase(student.id)
                    && existingVoucher.getSemester().equalsIgnoreCase(validatedSemester)) {
                throw new IllegalArgumentException(
                        "A voucher already exists for this student and semester.");
            }
        }

        BigDecimal validatedTuition = validateFeeAmount(tuitionFee, "Tuition fee");
        BigDecimal validatedLab = validateFeeAmount(labFee, "Lab fee");
        BigDecimal validatedOther = validateFeeAmount(otherCharges, "Other charges");
        if (validatedTuition.add(validatedLab).add(validatedOther).signum() <= 0) {
            throw new IllegalArgumentException("The total fees must be greater than zero.");
        }
        if (dueDate == null) {
            throw new IllegalArgumentException("Due date is required.");
        }

        if (!Double.isFinite(student.gpa)) {
            throw new IllegalArgumentException("Student GPA must be between 0.0 and 4.0.");
        }
        BigDecimal gpa = BigDecimal.valueOf(student.gpa);
        if (gpa.signum() < 0 || gpa.compareTo(BigDecimal.valueOf(4)) > 0) {
            throw new IllegalArgumentException("Student GPA must be between 0.0 and 4.0.");
        }
        BigDecimal discountRate = discountRateFor(gpa);
        BigDecimal discountAmount = validatedTuition.multiply(discountRate)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount = validatedTuition.subtract(discountAmount)
                .add(validatedLab).add(validatedOther);

        String voucherId = String.format(Locale.ROOT, "V-%04d", nextVoucherNumber);
        Voucher voucher = new Voucher(voucherId, student.id, validatedSemester, validatedTuition,
                validatedLab, validatedOther, discountAmount, amount, dueDate, LocalDateTime.now());
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

    private static BigDecimal discountRateFor(BigDecimal gpa) {
        if (gpa.compareTo(GPA_TIER_ONE) >= 0) {
            return DISCOUNT_TIER_ONE;
        }
        if (gpa.compareTo(GPA_TIER_TWO) >= 0) {
            return DISCOUNT_TIER_TWO;
        }
        if (gpa.compareTo(GPA_TIER_THREE) >= 0) {
            return DISCOUNT_TIER_THREE;
        }
        return BigDecimal.ZERO;
    }

    private static String validateSemester(String semester) {
        if (semester == null || semester.trim().isEmpty()) {
            throw new IllegalArgumentException("Semester label cannot be empty.");
        }
        String validatedSemester = semester.trim();
        if (validatedSemester.length() > 50) {
            throw new IllegalArgumentException("Semester label cannot exceed 50 characters.");
        }
        return validatedSemester;
    }

    public Payment recordPayment(String voucherId, BigDecimal amount) {
        return recordPayment(voucherId, amount, PaymentMethod.CASH, LocalDate.now());
    }

    public Payment recordPayment(String voucherId, BigDecimal amount, PaymentMethod method,
            LocalDate paymentDate) {
        Voucher voucher = findVoucherById(voucherId);
        if (voucher == null) {
            throw new IllegalArgumentException("Voucher not found.");
        }

        BigDecimal validatedAmount = validateAmount(amount);
        if (validatedAmount.compareTo(voucher.getBalance()) > 0) {
            throw new IllegalArgumentException("Payment cannot exceed the voucher balance of "
                    + voucher.getBalance().toPlainString() + ".");
        }
        if (method == null || method == PaymentMethod.UNSPECIFIED) {
            throw new IllegalArgumentException("Select Cash, Bank or Online as the payment method.");
        }
        if (paymentDate == null) {
            throw new IllegalArgumentException("Payment date is required.");
        }

        Payment payment = new Payment(String.format(Locale.ROOT, "R-%04d", nextReceiptNumber),
                validatedAmount, method, paymentDate.atStartOfDay());
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

    private static BigDecimal validateFeeAmount(BigDecimal amount, String fieldName) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    fieldName + " can have no more than two decimal places.");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
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
                if (lineNumber == 1 && (line.equals(VOUCHER_HEADER)
                        || line.equals(LEGACY_VOUCHER_HEADER))) {
                    continue;
                }
                List<String> fields = parseCsvLine(line);
                String voucherId = requireText(fields.get(0), "voucher ID");
                String studentId = requireText(fields.get(1), "student ID");
                int voucherNumber = parseVoucherNumber(voucherId);
                if (findVoucherById(voucherId) != null) {
                    throw new IllegalArgumentException("Duplicate voucher ID: " + voucherId);
                }

                Voucher voucher;
                if (fields.size() == 4) {
                    BigDecimal amount = parseStoredAmount(fields.get(2));
                    LocalDateTime issuedAt = LocalDateTime.parse(fields.get(3), DATE_FORMAT);
                    voucher = new Voucher(voucherId, studentId, "Legacy", amount,
                            BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                            BigDecimal.ZERO.setScale(2), amount, issuedAt.toLocalDate(), issuedAt);
                } else if (fields.size() == 10) {
                    String semester = validateSemester(fields.get(2));
                    BigDecimal tuitionFee = validateFeeAmount(
                            new BigDecimal(fields.get(3)), "Tuition fee");
                    BigDecimal labFee = validateFeeAmount(new BigDecimal(fields.get(4)), "Lab fee");
                    BigDecimal otherCharges = validateFeeAmount(
                            new BigDecimal(fields.get(5)), "Other charges");
                    BigDecimal discount = validateFeeAmount(
                            new BigDecimal(fields.get(6)), "Discount");
                    BigDecimal amount = parseStoredAmount(fields.get(7));
                    LocalDate dueDate = LocalDate.parse(fields.get(8));
                    LocalDateTime issuedAt = LocalDateTime.parse(fields.get(9), DATE_FORMAT);
                    if (discount.compareTo(tuitionFee) > 0) {
                        throw new IllegalArgumentException("Discount cannot exceed tuition fee.");
                    }
                    if (tuitionFee.subtract(discount).add(labFee).add(otherCharges)
                            .compareTo(amount) != 0) {
                        throw new IllegalArgumentException("Voucher total does not match its fees.");
                    }
                    for (Voucher existingVoucher : vouchers) {
                        if (existingVoucher.getStudentId().equalsIgnoreCase(studentId)
                                && existingVoucher.getSemester().equalsIgnoreCase(semester)) {
                            throw new IllegalArgumentException(
                                    "Duplicate voucher for student and semester.");
                        }
                    }
                    voucher = new Voucher(voucherId, studentId, semester, tuitionFee, labFee,
                            otherCharges, discount, amount, dueDate, issuedAt);
                } else {
                    throw new IllegalArgumentException("Expected 4 or 10 CSV fields.");
                }
                vouchers.add(voucher);
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
                if (lineNumber == 1 && (line.equals(PAYMENT_HEADER)
                        || line.equals(LEGACY_PAYMENT_HEADER))) {
                    continue;
                }
                List<String> fields = parseCsvLine(line);
                if (fields.size() != 4 && fields.size() != 5) {
                    throw new IllegalArgumentException("Expected 4 or 5 CSV fields.");
                }

                String receiptId = requireText(fields.get(0), "receipt ID");
                String voucherId = requireText(fields.get(1), "voucher ID");
                int receiptNumber = parseReceiptNumber(receiptId);
                if (!receiptIds.add(receiptId.toUpperCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate receipt ID: " + receiptId);
                }

                Voucher voucher = findVoucherById(voucherId);
                if (voucher == null) {
                    throw new IllegalArgumentException(
                            "Payment refers to unknown voucher ID: " + voucherId);
                }
                BigDecimal amount = parseStoredAmount(fields.get(2));
                PaymentMethod method = fields.size() == 5
                        ? PaymentMethod.fromStoredValue(fields.get(3))
                        : PaymentMethod.UNSPECIFIED;
                if (amount.compareTo(voucher.getBalance()) > 0) {
                    throw new IllegalArgumentException(
                            "Payments exceed the balance for voucher ID: " + voucherId);
                }
                String paidAtValue = fields.get(fields.size() - 1);
                LocalDateTime paidAt = parsePaymentDate(paidAtValue);
                voucher.addPayment(new Payment(receiptId, amount, method, paidAt));
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
            lines.add(csvRow(voucher.id, voucher.studentId, voucher.semester,
                    voucher.tuitionFee.toPlainString(), voucher.labFee.toPlainString(),
                    voucher.otherCharges.toPlainString(), voucher.discountAmount.toPlainString(),
                    voucher.amount.toPlainString(), voucher.getDueDate(), voucher.getIssuedAt()));
        }
        return lines;
    }

    private List<String> paymentCsvLines() {
        List<String> lines = new ArrayList<>();
        lines.add(PAYMENT_HEADER);
        for (Voucher voucher : vouchers) {
            for (Payment payment : voucher.payments) {
                lines.add(csvRow(payment.receiptId, voucher.id, payment.amount.toPlainString(),
                        payment.method.getDisplayName(), payment.getPaidAt()));
            }
        }
        return lines;
    }

    private static LocalDateTime parsePaymentDate(String value) {
        try {
            return LocalDate.parse(value).atStartOfDay();
        } catch (java.time.format.DateTimeParseException e) {
            return LocalDateTime.parse(value, DATE_FORMAT);
        }
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

    private static int parseReceiptNumber(String receiptId) {
        if (receiptId.startsWith("R-")) {
            return parseIdentifierNumber(receiptId, "R-");
        }
        return parseIdentifierNumber(receiptId, "FR-");
    }

    private static int parseVoucherNumber(String identifier) {
        if (identifier.startsWith("V-")) {
            return parseIdentifierNumber(identifier, "V-");
        }
        return parseIdentifierNumber(identifier, "FV-");
    }

    private static IllegalStateException persistenceLoadError(Path file, int lineNumber,
            Exception cause) {
        return new IllegalStateException("Unable to load fee data from " + file
                + (lineNumber > 0 ? " at line " + lineNumber : "") + ".", cause);
    }

    public static final class Voucher {
        private final String id;
        private final String studentId;
        private final String semester;
        private final BigDecimal tuitionFee;
        private final BigDecimal labFee;
        private final BigDecimal otherCharges;
        private final BigDecimal discountAmount;
        private final BigDecimal amount;
        private final LocalDate dueDate;
        private final LocalDateTime issuedAt;
        private final List<Payment> payments = new ArrayList<>();

        private Voucher(String id, String studentId, String semester, BigDecimal tuitionFee,
                BigDecimal labFee, BigDecimal otherCharges, BigDecimal discountAmount,
                BigDecimal amount, LocalDate dueDate, LocalDateTime issuedAt) {
            this.id = id;
            this.studentId = studentId;
            this.semester = semester;
            this.tuitionFee = tuitionFee;
            this.labFee = labFee;
            this.otherCharges = otherCharges;
            this.discountAmount = discountAmount;
            this.amount = amount;
            this.dueDate = dueDate;
            this.issuedAt = issuedAt;
        }

        public String getId() {
            return id;
        }

        public String getStudentId() {
            return studentId;
        }

        public String getSemester() {
            return semester;
        }

        public BigDecimal getTuitionFee() {
            return tuitionFee;
        }

        public BigDecimal getLabFee() {
            return labFee;
        }

        public BigDecimal getOtherCharges() {
            return otherCharges;
        }

        public BigDecimal getDiscountAmount() {
            return discountAmount;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getDueDate() {
            return dueDate.toString();
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

        public BigDecimal getLateFee(LocalDate asOfDate) {
            if (asOfDate == null) {
                throw new IllegalArgumentException("As-of date is required.");
            }
            if (getBalance().signum() <= 0 || !asOfDate.isAfter(dueDate)) {
                return BigDecimal.ZERO.setScale(2);
            }

            long overdueDays = ChronoUnit.DAYS.between(dueDate, asOfDate);
            BigDecimal calculatedFee = DAILY_LATE_FEE.multiply(BigDecimal.valueOf(overdueDays));
            BigDecimal feeCap = amount.multiply(LATE_FEE_CAP_RATE)
                    .setScale(2, RoundingMode.HALF_UP);
            return calculatedFee.min(feeCap);
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
        private final PaymentMethod method;
        private final LocalDateTime paidAt;

        private Payment(String receiptId, BigDecimal amount, PaymentMethod method,
                LocalDateTime paidAt) {
            this.receiptId = receiptId;
            this.amount = amount;
            this.method = method;
            this.paidAt = paidAt;
        }

        public String getReceiptId() {
            return receiptId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public PaymentMethod getMethod() {
            return method;
        }

        public String getPaidAt() {
            return paidAt.format(DATE_FORMAT);
        }
    }

    public enum PaymentMethod {
        CASH("Cash"),
        BANK("Bank"),
        ONLINE("Online"),
        UNSPECIFIED("Unspecified");

        private final String displayName;

        PaymentMethod(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public static PaymentMethod fromInput(String input) {
            if (input != null) {
                for (PaymentMethod method : values()) {
                    if (method != UNSPECIFIED
                            && method.displayName.equalsIgnoreCase(input.trim())) {
                        return method;
                    }
                }
            }
            throw new IllegalArgumentException("Enter Cash, Bank or Online.");
        }

        private static PaymentMethod fromStoredValue(String value) {
            for (PaymentMethod method : values()) {
                if (method.displayName.equalsIgnoreCase(value)) {
                    return method;
                }
            }
            throw new IllegalArgumentException("Invalid stored payment method: " + value);
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
