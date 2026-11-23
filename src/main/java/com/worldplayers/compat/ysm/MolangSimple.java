package com.worldplayers.compat.ysm;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Molang 求值器（第 ③ 步用；第 ④ 步会扩展它成完整的动画求值环境）。
 *
 * <p>只做「默认帧」需要的那点事：</p>
 * <ul>
 *   <li>数字、四则、取余、括号、一元负号；</li>
 *   <li>比较 {@code < > <= >= == !=}、逻辑 {@code && || !}、三元 {@code a ? b : c}；</li>
 *   <li>{@code ??}：左边"没定义"就用右边（YSM 里大量 {@code v.player_size??1}）；</li>
 *   <li>{@code math.sin/cos/...}、{@code ysm.second_order(...)} 之类的函数调用；</li>
 *   <li>任何变量（{@code q.* / query.* / v.* / ysm.* / t.*}）都当成**未定义**，
 *       参与算术按 0 算 —— 默认帧本来就没有真实运行时的值。</li>
 * </ul>
 *
 * <p>算不出来（语法不认识 / 有副作用语句）返回 {@code null}，调用方就跳过这个通道——
 * 宁可不动，也不瞎藏部件。</p>
 */
public final class MolangSimple {
    /** 未定义标记：参与算术算 0，遇到 ?? 让位给右边。 */
    private static final Object UNDEFINED = new Object();

    @javax.annotation.Nullable
    public static Float eval(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            List<Token> tokens = tokenize(expression);
            Parser parser = new Parser(tokens);
            Object value = parser.parse();
            if (parser.hasMore()) {
                return null;
            }
            return value == UNDEFINED ? 0.0F : (float) num(value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private record Token(String kind, String text) {
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < source.length()
                    && Character.isDigit(source.charAt(i + 1)))) {
                int start = i;
                while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token("num", source.substring(start, i)));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
                        || source.charAt(i) == '_' || source.charAt(i) == '.' || source.charAt(i) == ':')) {
                    i++;
                }
                tokens.add(new Token("id", source.substring(start, i)));
                continue;
            }
            String two = i + 1 < source.length() ? source.substring(i, i + 2) : "";
            if (List.of("??", "&&", "||", "<=", ">=", "==", "!=").contains(two)) {
                tokens.add(new Token("op", two));
                i += 2;
                continue;
            }
            if ("+-*/%()<>,?:=!".indexOf(c) >= 0) {
                tokens.add(new Token("op", String.valueOf(c)));
                i++;
                continue;
            }
            throw new IllegalArgumentException("不认识的字符 " + c);
        }
        return tokens;
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int index;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        boolean hasMore() {
            return this.index < this.tokens.size();
        }

        @javax.annotation.Nullable
        private Token peek() {
            return hasMore() ? this.tokens.get(this.index) : null;
        }

        private Token next() {
            return this.tokens.get(this.index++);
        }

        private boolean take(String text) {
            Token token = peek();
            if (token != null && token.text().equals(text)) {
                this.index++;
                return true;
            }
            return false;
        }

        Object parse() {
            return ternary();
        }

        private Object ternary() {
            Object condition = coalesce();
            if (take("?")) {
                Object whenTrue = ternary();
                if (!take(":")) {
                    throw new IllegalArgumentException("三元缺冒号");
                }
                Object whenFalse = ternary();
                return truth(condition) ? whenTrue : whenFalse;
            }
            return condition;
        }

        private Object coalesce() {
            Object left = or();
            while (take("??")) {
                Object right = or();
                left = left == UNDEFINED ? right : left;
            }
            return left;
        }

        private Object or() {
            Object left = and();
            while (take("||")) {
                Object right = and();
                left = truth(left) || truth(right) ? 1.0D : 0.0D;
            }
            return left;
        }

        private Object and() {
            Object left = equality();
            while (take("&&")) {
                Object right = equality();
                left = truth(left) && truth(right) ? 1.0D : 0.0D;
            }
            return left;
        }

        private Object equality() {
            Object left = relational();
            while (true) {
                if (take("==")) {
                    left = num(left) == num(relational()) ? 1.0D : 0.0D;
                } else if (take("!=")) {
                    left = num(left) != num(relational()) ? 1.0D : 0.0D;
                } else {
                    return left;
                }
            }
        }

        private Object relational() {
            Object left = addition();
            while (true) {
                Token token = peek();
                if (token == null || !List.of("<", ">", "<=", ">=").contains(token.text())) {
                    return left;
                }
                String op = next().text();
                double right = num(addition());
                double a = num(left);
                boolean result = switch (op) {
                    case "<" -> a < right;
                    case ">" -> a > right;
                    case "<=" -> a <= right;
                    default -> a >= right;
                };
                left = result ? 1.0D : 0.0D;
            }
        }

        private Object addition() {
            Object left = multiplication();
            while (true) {
                if (take("+")) {
                    left = num(left) + num(multiplication());
                } else if (take("-")) {
                    left = num(left) - num(multiplication());
                } else {
                    return left;
                }
            }
        }

        private Object multiplication() {
            Object left = unary();
            while (true) {
                if (take("*")) {
                    left = num(left) * num(unary());
                } else if (take("/")) {
                    double divisor = num(unary());
                    left = divisor == 0.0D ? 0.0D : num(left) / divisor;
                } else if (take("%")) {
                    double divisor = num(unary());
                    left = divisor == 0.0D ? 0.0D : num(left) % divisor;
                } else {
                    return left;
                }
            }
        }

        private Object unary() {
            if (take("-")) {
                return -num(unary());
            }
            if (take("+")) {
                return num(unary());
            }
            if (take("!")) {
                return truth(unary()) ? 0.0D : 1.0D;
            }
            return atom();
        }

        private Object atom() {
            Token token = next();
            if (token.kind().equals("num")) {
                return Double.parseDouble(token.text());
            }
            if (token.text().equals("(")) {
                Object value = ternary();
                if (!take(")")) {
                    throw new IllegalArgumentException("缺右括号");
                }
                return value;
            }
            if (token.kind().equals("id")) {
                if (take("(")) {
                    List<Object> args = new ArrayList<>();
                    if (!take(")")) {
                        args.add(ternary());
                        while (take(",")) {
                            args.add(ternary());
                        }
                        if (!take(")")) {
                            throw new IllegalArgumentException("调用缺右括号");
                        }
                    }
                    return call(token.text(), args);
                }
                return UNDEFINED;
            }
            throw new IllegalArgumentException("不认识的值 " + token.text());
        }
    }

    private static Object call(String name, List<Object> args) {
        String function = name;
        int dot = function.lastIndexOf(':');
        if (dot >= 0) {
            function = function.substring(dot + 1);
        }
        return switch (function) {
            case "math.sin" -> Math.sin(Math.toRadians(arg(args, 0)));
            case "math.cos" -> Math.cos(Math.toRadians(arg(args, 0)));
            case "math.tan" -> Math.tan(Math.toRadians(arg(args, 0)));
            case "math.asin" -> Math.toDegrees(Math.asin(clampUnit(arg(args, 0))));
            case "math.acos" -> Math.toDegrees(Math.acos(clampUnit(arg(args, 0))));
            case "math.atan" -> Math.toDegrees(Math.atan(arg(args, 0)));
            case "math.atan2" -> Math.toDegrees(Math.atan2(arg(args, 0), arg(args, 1)));
            case "math.abs" -> Math.abs(arg(args, 0));
            case "math.floor" -> Math.floor(arg(args, 0));
            case "math.ceil" -> Math.ceil(arg(args, 0));
            case "math.round" -> Math.round(arg(args, 0));
            case "math.trunc" -> (double) (long) arg(args, 0);
            case "math.sqrt" -> Math.sqrt(Math.max(0.0D, arg(args, 0)));
            case "math.pow" -> Math.pow(arg(args, 0), arg(args, 1));
            case "math.exp" -> Math.exp(arg(args, 0));
            case "math.ln" -> Math.log(Math.max(1.0E-4D, arg(args, 0)));
            case "math.mod" -> arg(args, 1) == 0.0D ? 0.0D : arg(args, 0) % arg(args, 1);
            case "math.min" -> args.stream().mapToDouble(MolangSimple::num).min().orElse(0.0D);
            case "math.max" -> args.stream().mapToDouble(MolangSimple::num).max().orElse(0.0D);
            case "math.clamp" -> Math.min(Math.max(arg(args, 0), arg(args, 1)), arg(args, 2));
            case "math.random" -> 0.0D;
            case "random" -> 0.0D;
            // 二阶缓动 / 物理量：默认帧里就是静止 → 0
            case "second_order", "first_order" -> 0.0D;
            default -> 0.0D;
        };
    }

    private static double arg(List<Object> args, int index) {
        return index < args.size() ? num(args.get(index)) : 0.0D;
    }

    private static double clampUnit(double value) {
        return Math.max(-1.0D, Math.min(1.0D, value));
    }

    private static double num(Object value) {
        if (value == UNDEFINED || value == null) {
            return 0.0D;
        }
        return (double) value;
    }

    private static boolean truth(Object value) {
        return num(value) != 0.0D;
    }

    private MolangSimple() {
    }
}
