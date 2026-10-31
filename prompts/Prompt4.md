Role: Act as a Senior Java Software Developer

Task: 

Payment module changes 
Record the amount, method (Cash, Bank or Online) and date against a voucher.
Allow partial payments. Reject amounts above the outstanding balance.
Generate a receipt number (R-0001, R-0002, ...).
with late fee criteria 
For an as-of date entered by the user, a voucher with an outstanding balance
past its due date incurs PKR 100 per day, capped at 10% of the net payable
amount. The fine is calculated on demand and is not stored.

Context: Project workspace @FeeService.java
Constraints: Do not modify the core structure and place your code in the FeeService. no need to add test cases. as this is an console application so make sure user inputs are properly validated
Output: Provide a concise report of the files gets chnaged.
Verification: Ensure code is in compleable and in working conditions.
