package com.codevisualizer.service;

import com.codevisualizer.dto.SampleProgram;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SampleService {

    private static final List<SampleProgram> SAMPLES = List.of(
            new SampleProgram("factorial", "For loop · Factorial",
                    "See how a for loop multiplies numbers step by step.",
                    """
                    public class Factorial {
                        public static void main(String[] args) {
                            int n = 5;
                            long fact = 1;
                            for (int i = 1; i <= n; i++) {
                                fact = fact * i;
                            }
                            System.out.println("5! = " + fact);
                        }
                    }
                    """),
            new SampleProgram("evenodd", "If / else · Even or odd",
                    "A single condition decides between two branches.",
                    """
                    public class EvenOdd {
                        public static void main(String[] args) {
                            int number = 7;
                            if (number % 2 == 0) {
                                System.out.println(number + " is even");
                            } else {
                                System.out.println(number + " is odd");
                            }
                        }
                    }
                    """),
            new SampleProgram("arraysum", "Array · Sum of numbers",
                    "Watch an array and a running total change on each pass.",
                    """
                    public class ArraySum {
                        public static void main(String[] args) {
                            int[] numbers = {10, 20, 30, 40, 50};
                            int sum = 0;
                            for (int i = 0; i < numbers.length; i++) {
                                sum += numbers[i];
                            }
                            System.out.println("Sum = " + sum);
                        }
                    }
                    """),
            new SampleProgram("bubblesort", "Nested loops · Bubble sort",
                    "Two loops and an if statement sort a small array in place.",
                    """
                    public class BubbleSort {
                        public static void main(String[] args) {
                            int[] arr = {5, 2, 9, 1, 3};
                            int n = arr.length;
                            for (int i = 0; i < n - 1; i++) {
                                for (int j = 0; j < n - 1 - i; j++) {
                                    if (arr[j] > arr[j + 1]) {
                                        int temp = arr[j];
                                        arr[j] = arr[j + 1];
                                        arr[j + 1] = temp;
                                    }
                                }
                            }
                            for (int i = 0; i < n; i++) {
                                System.out.println(arr[i]);
                            }
                        }
                    }
                    """),
            new SampleProgram("fibonacci", "Recursion · Fibonacci",
                    "Calls push new stack frames, then pop as values return.",
                    """
                    public class Fibonacci {
                        public static void main(String[] args) {
                            int result = fib(5);
                            System.out.println("fib(5) = " + result);
                        }

                        static int fib(int n) {
                            if (n <= 1) {
                                return n;
                            }
                            return fib(n - 1) + fib(n - 2);
                        }
                    }
                    """),
            new SampleProgram("countdown", "While loop · Countdown",
                    "The loop repeats until the condition becomes false.",
                    """
                    public class Countdown {
                        public static void main(String[] args) {
                            int count = 3;
                            while (count > 0) {
                                System.out.println(count);
                                count--;
                            }
                            System.out.println("Liftoff!");
                        }
                    }
                    """),
            new SampleProgram("method", "Method call · Area",
                    "Step out of main, into a helper method, and back again.",
                    """
                    public class AreaCalculator {
                        public static void main(String[] args) {
                            int width = 4;
                            int height = 6;
                            int area = rectangleArea(width, height);
                            System.out.println("Area = " + area);
                        }

                        static int rectangleArea(int width, int height) {
                            int area = width * height;
                            return area;
                        }
                    }
                    """));

    public List<SampleProgram> samples() {
        return SAMPLES;
    }
}
