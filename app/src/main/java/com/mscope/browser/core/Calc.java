package com.mscope.browser.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

/**
 * 一个只有数字和运算符的递归下降求值器。
 *
 * <p>为什么不用 {@code ScriptEngine} 或者让模型自己算：本地小模型三位数以上的乘除就会算错，
 * 而「算出错了」是用户最难发现的一种错 —— 它看起来像个答案。这里支持
 * {@code + - * / % ^ 括号 一元负号}，以及 {@code pi/e} 两个常量、{@code sqrt(x)} 一个函数，
 * 足够覆盖日常；遇到任何看不懂的东西直接抛错，绝不猜。
 */
public final class Calc {

    private Calc() {
    }

    /** 求值结果（十进制，保留到 double 精度）。 */
    public static double eval(String expr) {
        if (expr == null || expr.trim().isEmpty()) throw new IllegalArgumentException("empty expression");
        Parser p = new Parser(expr);
        double v = p.expr();
        p.skipWs();
        if (!p.eof()) throw new IllegalArgumentException("unexpected '" + p.peek() + "' at " + p.pos);
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new ArithmeticException("result is not finite");
        return v;
    }

    /** 求值并直接返回格式化的字符串（供工具层使用）。 */
    public static String solve(String expr) {
        return format(eval(expr));
    }

    /** 格式化：整数不带小数点，其余最多 10 位有效数字，避免 0.30000000000000004。 */
    public static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        BigDecimal bd = new BigDecimal(v, new MathContext(10));
        return bd.stripTrailingZeros().toPlainString();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        char peek() {
            return s.charAt(pos);
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        /** expr := term (('+'|'-') term)* */
        double expr() {
            double v = term();
            while (true) {
                skipWs();
                if (eof()) return v;
                char c = peek();
                if (c == '+') {
                    pos++;
                    v += term();
                } else if (c == '-') {
                    pos++;
                    v -= term();
                } else {
                    return v;
                }
            }
        }

        /** term := unary (('*'|'/'|'%') unary)* */
        double term() {
            double v = unary();
            while (true) {
                skipWs();
                if (eof()) return v;
                char c = peek();
                if (c == '*') {
                    pos++;
                    v *= unary();
                } else if (c == '/') {
                    pos++;
                    double d = unary();
                    if (d == 0) throw new ArithmeticException("division by zero");
                    v /= d;
                } else if (c == '%' && modAhead()) {
                    pos++;
                    double d = unary();
                    if (d == 0) throw new ArithmeticException("division by zero");
                    v %= d;
                } else {
                    return v;
                }
            }
        }

        /**
         * 判断 % 是取模还是「百分号」。
         *
         * <p>日常写法里 {@code 10%3} 是取模，但 {@code 15%} 是「百分之十五」。
         * 这里只在 % 后面紧跟数字或左括号时才当取模，其余情况让它去当后缀百分号，
         * 否则「涨了 15%」这种句子会被当成算式算错。
         */
        boolean modAhead() {
            int j = pos + 1;
            while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
            if (j >= s.length()) return false;
            char c = s.charAt(j);
            return Character.isDigit(c) || c == '(';
        }

        /** unary := ('-'|'+') unary | power */
        double unary() {
            skipWs();
            if (eof()) throw new IllegalArgumentException("unexpected end");
            char c = peek();
            if (c == '-') {
                pos++;
                return -unary();
            }
            if (c == '+') {
                pos++;
                return unary();
            }
            return power();
        }

        /** power := atom ('^' unary)? —— 右结合，2^3^2 = 512 */
        double power() {
            double base = atom();
            while (true) {
                skipWs();
                if (eof()) return base;
                char c = peek();
                if (c == '^') {
                    pos++;
                    base = Math.pow(base, unary());
                    continue;
                }
                if (c == '%' && !modAhead()) {
                    // 后缀百分号：15% → 0.15；后面还跟着操作数说明是取模，交给 term()
                    pos++;
                    base = base / 100.0;
                    continue;
                }
                return base;
            }
        }

        double atom() {
            skipWs();
            if (eof()) throw new IllegalArgumentException("unexpected end");
            char c = peek();
            if (c == '(') {
                pos++;
                double v = expr();
                skipWs();
                if (eof() || peek() != ')') throw new IllegalArgumentException("missing ')'");
                pos++;
                return v;
            }
            if (Character.isDigit(c) || c == '.') return number();
            if (Character.isLetter(c)) return ident();
            throw new IllegalArgumentException("unexpected '" + c + "' at " + pos);
        }

        double number() {
            int start = pos;
            boolean dot = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (num.isEmpty() || ".".equals(num)) throw new IllegalArgumentException("bad number at " + start);
            return Double.parseDouble(num);
        }

        double ident() {
            int start = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
            String name = s.substring(start, pos).toLowerCase(Locale.ROOT);
            switch (name) {
                case "pi":
                    return Math.PI;
                case "e":
                    return Math.E;
                case "sqrt": {
                    double v = call();
                    if (v < 0) throw new ArithmeticException("sqrt of negative");
                    return Math.sqrt(v);
                }
                default:
                    throw new IllegalArgumentException("unknown name '" + name + "'");
            }
        }

        /** 函数调用的括号参数。 */
        double call() {
            skipWs();
            if (eof() || peek() != '(') throw new IllegalArgumentException("expected '('");
            pos++;
            double v = expr();
            skipWs();
            if (eof() || peek() != ')') throw new IllegalArgumentException("missing ')'");
            pos++;
            return v;
        }
    }
}
