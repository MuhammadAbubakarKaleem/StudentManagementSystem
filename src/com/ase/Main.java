package com.ase;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Scanner;

import com.ase.model.Student;
import com.ase.service.FeeService;
import com.ase.service.StudentService;

public class Main {
    private static final StudentService service = new StudentService();
    private static final FeeService feeService = new FeeService(service.getAllStudents());

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        System.out.println("========================================");
        System.out.println(" Welcome to Student Management System ");
        System.out.println("========================================");

        while (running) {
            printMenu();
            System.out.print("Select an option: ");

            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine().trim();
            int choice;
            try {
                choice = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.out.println("Invalid input type. Enter a menu number.");
                continue;
            }

            switch (choice) {
                case 1:
                    listAllStudents();
                    break;
                case 2:
                    addNewStudentUI(scanner);
                    break;
                case 3:
                    triggerSearchUI(scanner);
                    break;
                case 4:
                    System.out.println("Average GPA: " + service.calculateAverageGpa());
                    break;
                case 9:
                    runFeeModule(scanner);
                    break;
                case 0:
                    running = false;
                    System.out.println("Exiting application...");
                    break;
                default:
                    System.out.println("Invalid option. Try again.");
            }
        }
        scanner.close();
    }

    private static void printMenu() {
        System.out.println("\n--- MAIN MENU ---");
        System.out.println("1. View All Students");
        System.out.println("2. Add New Student");
        System.out.println("3. Find Student by ID (Buggy)");
        System.out.println("4. Display Class GPA Average (Buggy)");
        System.out.println("9. Fee Management");
        System.out.println("0. Exit");
    }

    private static void runFeeModule(Scanner scanner) {
        boolean inFeeModule = true;
        while (inFeeModule) {
            System.out.println("\n--- FEE MANAGEMENT ---");
            System.out.println("1. Generate Voucher");
            System.out.println("2. Record Payment");
            System.out.println("3. Fee Statement");
            System.out.println("4. Defaulters List (outstanding balances)");
            System.out.println("0. Back to Main Menu");
            System.out.print("Select an option: ");

            if (!scanner.hasNextLine()) {
                return;
            }
            String input = scanner.nextLine().trim();
            int choice;
            try {
                choice = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Enter a menu number.");
                continue;
            }

            switch (choice) {
                case 1:
                    generateFeeVoucher(scanner);
                    break;
                case 2:
                    recordFeePayment(scanner);
                    break;
                case 3:
                    displayFeeStatement(scanner);
                    break;
                case 4:
                    displayDefaulters();
                    break;
                case 0:
                    inFeeModule = false;
                    break;
                default:
                    System.out.println("Invalid option. Try again.");
            }
        }
    }

    private static void generateFeeVoucher(Scanner scanner) {
        String studentId = readRequiredInput(scanner, "Enter Student ID: ");
        if (studentId == null) {
            return;
        }
        String semester = readRequiredInput(scanner, "Enter semester (for example, Fall 2026): ");
        if (semester == null) {
            return;
        }
        BigDecimal tuitionFee = readNonNegativeAmount(scanner, "Enter tuition fee: ");
        if (tuitionFee == null) {
            return;
        }
        BigDecimal labFee = readNonNegativeAmount(scanner, "Enter lab fee: ");
        if (labFee == null) {
            return;
        }
        BigDecimal otherCharges = readNonNegativeAmount(scanner, "Enter other charges: ");
        if (otherCharges == null) {
            return;
        }
        LocalDate dueDate = readDueDate(scanner);
        if (dueDate == null) {
            return;
        }

        try {
            FeeService.Voucher voucher = feeService.generateVoucher(studentId, semester,
                    tuitionFee, labFee, otherCharges, dueDate);
            System.out.println("Voucher generated successfully.");
            printVoucher(voucher);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        } catch (IllegalStateException e) {
            System.out.println("Unable to generate voucher: " + e.getMessage());
        }
    }

    private static void recordFeePayment(Scanner scanner) {
        String voucherId = readRequiredInput(scanner, "Enter Voucher ID: ");
        if (voucherId == null) {
            return;
        }
        BigDecimal amount = readPositiveAmount(scanner, "Enter payment amount: ");
        if (amount == null) {
            return;
        }

        try {
            FeeService.Payment payment = feeService.recordPayment(voucherId, amount);
            System.out.println("Payment recorded: " + payment.getAmount().toPlainString()
                    + " on " + payment.getPaidAt());
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
    }

    private static void displayFeeStatement(Scanner scanner) {
        String studentId = readRequiredInput(scanner, "Enter Student ID: ");
        if (studentId == null) {
            return;
        }
        Student student = feeService.findStudentById(studentId);
        if (student == null) {
            System.out.println("Student not found.");
            return;
        }

        List<FeeService.Voucher> vouchers = feeService.getFeeStatement(studentId);
        System.out.println("\nFee statement for " + student.n + " (" + student.id + ")");
        if (vouchers.isEmpty()) {
            System.out.println("No fee vouchers found.");
            return;
        }
        for (FeeService.Voucher voucher : vouchers) {
            printVoucher(voucher);
            for (FeeService.Payment payment : voucher.getPayments()) {
                System.out.println("  Payment: " + payment.getAmount().toPlainString()
                        + " on " + payment.getPaidAt());
            }
        }
    }

    private static void displayDefaulters() {
        List<FeeService.Defaulter> defaulters = feeService.getDefaulters();
        if (defaulters.isEmpty()) {
            System.out.println("No outstanding fee balances.");
            return;
        }
        System.out.println("\n--- STUDENTS WITH OUTSTANDING FEES ---");
        for (FeeService.Defaulter defaulter : defaulters) {
            System.out.println("ID: " + defaulter.getStudentId()
                    + " | Name: " + defaulter.getStudentName()
                    + " | Outstanding: " + defaulter.getOutstandingAmount().toPlainString()
                    + " | Unpaid vouchers: " + defaulter.getOutstandingVoucherCount());
        }
    }

    private static void printVoucher(FeeService.Voucher voucher) {
        System.out.println("Voucher: " + voucher.getId()
                + " | Student: " + voucher.getStudentId()
                + " | Semester: " + voucher.getSemester()
                + " | Tuition: " + voucher.getTuitionFee().toPlainString()
                + " | Lab: " + voucher.getLabFee().toPlainString()
                + " | Other charges: " + voucher.getOtherCharges().toPlainString()
                + " | Merit discount: " + voucher.getDiscountAmount().toPlainString()
                + " | Due date: " + voucher.getDueDate()
                + " | Issued: " + voucher.getIssuedAt()
                + " | Net payable: " + voucher.getAmount().toPlainString()
                + " | Paid: " + voucher.getPaidAmount().toPlainString()
                + " | Balance: " + voucher.getBalance().toPlainString());
    }

    private static String readRequiredInput(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            if (!scanner.hasNextLine()) {
                return null;
            }
            String input = scanner.nextLine().trim();
            if (!input.isEmpty()) {
                return input;
            }
            System.out.println("Input cannot be empty.");
        }
    }

    private static BigDecimal readPositiveAmount(Scanner scanner, String prompt) {
        while (true) {
            String input = readRequiredInput(scanner, prompt);
            if (input == null) {
                return null;
            }
            if (!input.matches("(?:[0-9]+(?:\\.[0-9]{0,2})?|\\.[0-9]{1,2})")) {
                System.out.println("Enter a valid amount, for example 125.50.");
                continue;
            }
            try {
                BigDecimal amount = new BigDecimal(input);
                if (amount.signum() <= 0) {
                    System.out.println("Amount must be greater than zero.");
                } else {
                    return amount.setScale(2);
                }
            } catch (NumberFormatException e) {
                System.out.println("Enter a valid amount, for example 125.50.");
            }
        }
    }

    private static BigDecimal readNonNegativeAmount(Scanner scanner, String prompt) {
        while (true) {
            String input = readRequiredInput(scanner, prompt);
            if (input == null) {
                return null;
            }
            if (!input.matches("(?:[0-9]+(?:\\.[0-9]{0,2})?|\\.[0-9]{1,2})")) {
                System.out.println("Enter a valid amount, for example 125.50.");
                continue;
            }
            try {
                return new BigDecimal(input).setScale(2);
            } catch (NumberFormatException e) {
                System.out.println("Enter a valid amount, for example 125.50.");
            }
        }
    }

    private static LocalDate readDueDate(Scanner scanner) {
        while (true) {
            String input = readRequiredInput(scanner, "Enter due date (YYYY-MM-DD): ");
            if (input == null) {
                return null;
            }
            try {
                return LocalDate.parse(input);
            } catch (DateTimeParseException e) {
                System.out.println("Enter a valid date in YYYY-MM-DD format.");
            }
        }
    }

    private static void listAllStudents() {
        List<Student> students = service.getAllStudents();
        if (students.isEmpty()) {
            System.out.println("No records found.");
            return;
        }
        for (Student s : students) {
            s.printInfo();
        }
    }

    private static void addNewStudentUI(Scanner scanner) {
        System.out.print("Enter ID: ");
        String id = scanner.nextLine();
        System.out.print("Enter Name: ");
        String name = scanner.nextLine();
        System.out.print("Enter Email: ");
        String email = scanner.nextLine();
        
        System.out.print("Enter GPA: ");
        double gpa = Double.parseDouble(scanner.nextLine());

        service.addStudent(new Student(id, name, email, gpa));
        System.out.println("Student added successfully!");
    }

    private static void triggerSearchUI(Scanner scanner) {
        System.out.print("Enter Student ID to Search: ");
        String id = scanner.nextLine();
        
        Student result = service.findStudentById(id);
        if (result != null) {
            result.printInfo();
        } else {
            System.out.println("Student not found.");
        }
    }
}
