package com.worldplayers.compat.ysm;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Molang 运行时（第 ④ 步）：把 YSM 动画里的表达式编译成小 AST，按每帧的环境求值。
 *
 * <p>支持范围（够跑 YSM 的 idle / walk / run / 并行动画）：</p>
 * <ul>
 *   <li>数字、四则、取余、一元、括号；</li>
 *   <li>比较、逻辑、三元、{@code ??}（左边没定义就用右边）；</li>
 *   <li>变量：{@code q./query.} 查询（未设置按 0）、{@code v./variable.} 变量（未赋值 = 未定义）、
 *       {@code t./temp.} 临时变量；{@code ysm.*} 当作查询（head_yaw / head_pitch / has_helmet…）；</li>
 *   <li>函数：{@code math.*}、{@code ysm.second_order/first_order}（先按 0）、其它未知函数返回 0；</li>
 *   <li>语句：分号分隔，支持 {@code v.x = expr} 这种赋值（YSM 物理动画大量用它）。</li>
 * </ul>
 *
 * <p>编译结果按表达式字符串缓存，同一个表达式只解析一次（每帧只求值）。</p>
 */
public final class Molang {
    /** 未定义标记。 */
    public static final Object UNDEFINED = new Object();

    /** 每帧的求值环境（同一帧内复用，别跨帧复用）。 */
    public static final class Env {
        public final Map<String, Double> queries = new HashMap<>();
        public final Map<String, Double> variables = new HashMap<>();
        public final Map<String, Double> temp = new HashMap<>();

        public Env query(String name, double value) {
            this.queries.put(name, value);
            return this;
        }

        public void reset() {
            this.variables.clear();
            this.temp.clear();
        }
    }

    /** 一条语句：可选的赋值目标 + 表达式。 */
    public static final class Program {
        private final String[] targets;
        private final Node[] expressions;

        private Program(String[] targets, Node[] expressions) {
            this.targets = targets;
            this.expressions = expressions;
        }

        /** 求值并把结果写回变量（赋值语句）；返回最后一条语句的值（未定义 → 0）。 */
        public float run(Env env) {
            double last = 0.0D;
            for (int i = 0; i < this.expressions.length; i++) {
                Object value = this.expressions[i].eval(env);
                double number = num(value);
                last = number;
                String target = this.targets[i];
                if (target != null && value != UNDEFINED) {
                    if (target.startsWith("v.") || target.startsWith("variable.")) {
                        env.variables.put(target, number);
                    } else if (target.startsWith("t.") || target.startsWith("temp.")) {
                        env.temp.put(target, number);
                    }
                }
            }
            return (float) last;
        }
    }

    public interface Node {
        Object eval(Env env);
    }

    private static final Map<String, Program> CACHE = new ConcurrentHashMap<>();

    /** 编译（带缓存）；语法不认识返回 null（调用方跳过这个通道）。 */
    @javax.annotation.Nullable
    public static Program compile(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        return CACHE.computeIfAbsent(source, text -> {
            try {
                return parseProgram(text);
            } catch (RuntimeException error) {
                return null;
            }
        });
    }

    /** 求值一个表达式（编译失败 → 0）。 */
    public static float eval(String source, Env env) {
        Program program = compile(source);
        return program == null ? 0.0F : program.run(env);
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private static Program parseProgram(String source) {
        String[] statements = splitStatements(source);
        String[] targets = new String[statements.length];
        Node[] nodes = new Node[statements.length];
        int count = 0;
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty() || "return".equals(trimmed)) {
                continue;
            }
            if (trimmed.startsWith("return ")) {
                trimmed = trimmed.substring("return ".length()).trim();
            }
            String target = null;
            int assign = findAssignment(trimmed);
            if (assign > 0) {
                target = trimmed.substring(0, assign).trim();
                trimmed = trimmed.substring(assign + 1).trim();
                if (!isIdentifier(target)) {
                    target = null;
                }
            }
            Parser parser = new Parser(tokenize(trimmed));
            Node node = parser.parseExpression();
            if (parser.hasMore()) {
                throw new IllegalArgumentException("表达式没读完：" + trimmed);
            }
            targets[count] = target;
            nodes[count] = node;
            count++;
        }
        return new Program(java.util.Arrays.copyOf(targets, count), java.util.Arrays.copyOf(nodes, count));
    }

    /** 按顶层分号切开（括号里的分号不算）。 */
    private static String[] splitStatements(String source) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ';' && depth == 0) {
                out.add(source.substring(start, i));
                start = i + 1;
            }
        }
        out.add(source.substring(start));
        return out.toArray(new String[0]);
    }

    /** 找到赋值号（不是 == / <= / >= / !=）的位置；没有返回 -1。 */
    private static int findAssignment(String source) {
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) != '=') {
                continue;
            }
            char before = i > 0 ? source.charAt(i - 1) : ' ';
            char after = i + 1 < source.length() ? source.charAt(i + 1) : ' ';
            if (before == '=' || before == '<' || before == '>' || before == '!' || after == '=') {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean isIdentifier(String text) {
        return text.matches("[A-Za-z_][A-Za-z0-9_.:]*");
    }

    private record Token(String kind, String text) {
    }

    private static java.util.List<Token> tokenize(String source) {
        java.util.List<Token> tokens = new java.util.ArrayList<>();
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
            if (c == '\'' || c == '"') {
                // ★ 字符串字面量：YSM 模型大量使用 q.is_item_name_any('Mainhand', 'bow') 这种调用。
                // 旧 tokenizer 不认引号 → 整个表达式编译失败 → 通道退化成常量 0 →
                // scale 通道被当成「三轴全 0」= 骨骼隐藏（大酒狐的左手就是这么消失的）。
                int start = i + 1;
                i++;
                while (i < source.length() && source.charAt(i) != c) {
                    i++;
                }
                tokens.add(new Token("str", source.substring(start, Math.min(i, source.length()))));
                if (i < source.length()) {
                    i++;
                }
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
                        || source.charAt(i) == '_' || source.charAt(i) == '.' || source.charAt(i) == ':'
                        || source.charAt(i) == '$')) {
                    i++;
                }
                tokens.add(new Token("id", source.substring(start, i)));
                continue;
            }
            String two = i + 1 < source.length() ? source.substring(i, i + 2) : "";
            if (java.util.List.of("??", "&&", "||", "<=", ">=", "==", "!=").contains(two)) {
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
        private final java.util.List<Token> tokens;
        private int index;

        Parser(java.util.List<Token> tokens) {
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

        Node parseExpression() {
            return ternary();
        }

        private Node ternary() {
            Node condition = coalesce();
            if (take("?")) {
                Node whenTrue = ternary();
                if (!take(":")) {
                    throw new IllegalArgumentException("三元缺冒号");
                }
                Node whenFalse = ternary();
                return env -> truth(condition.eval(env)) ? whenTrue.eval(env) : whenFalse.eval(env);
            }
            return condition;
        }

        private Node coalesce() {
            Node left = or();
            while (take("??")) {
                Node right = or();
                Node finalLeft = left;
                left = env -> {
                    Object value = finalLeft.eval(env);
                    return value == UNDEFINED ? right.eval(env) : value;
                };
            }
            return left;
        }

        private Node or() {
            Node left = and();
            while (take("||")) {
                Node right = and();
                Node finalLeft = left;
                left = env -> truth(finalLeft.eval(env)) || truth(right.eval(env)) ? 1.0D : 0.0D;
            }
            return left;
        }

        private Node and() {
            Node left = equality();
            while (take("&&")) {
                Node right = equality();
                Node finalLeft = left;
                left = env -> truth(finalLeft.eval(env)) && truth(right.eval(env)) ? 1.0D : 0.0D;
            }
            return left;
        }

        private Node equality() {
            Node left = relational();
            while (true) {
                if (take("==")) {
                    Node right = relational();
                    Node finalLeft = left;
                    left = env -> num(finalLeft.eval(env)) == num(right.eval(env)) ? 1.0D : 0.0D;
                } else if (take("!=")) {
                    Node right = relational();
                    Node finalLeft = left;
                    left = env -> num(finalLeft.eval(env)) != num(right.eval(env)) ? 1.0D : 0.0D;
                } else {
                    return left;
                }
            }
        }

        private Node relational() {
            Node left = addition();
            while (true) {
                Token token = peek();
                if (token == null || !java.util.List.of("<", ">", "<=", ">=").contains(token.text())) {
                    return left;
                }
                String op = next().text();
                Node right = addition();
                Node finalLeft = left;
                left = env -> {
                    double a = num(finalLeft.eval(env));
                    double b = num(right.eval(env));
                    boolean result = switch (op) {
                        case "<" -> a < b;
                        case ">" -> a > b;
                        case "<=" -> a <= b;
                        default -> a >= b;
                    };
                    return result ? 1.0D : 0.0D;
                };
            }
        }

        private Node addition() {
            Node left = multiplication();
            while (true) {
                if (take("+")) {
                    Node right = multiplication();
                    Node finalLeft = left;
                    left = env -> num(finalLeft.eval(env)) + num(right.eval(env));
                } else if (take("-")) {
                    Node right = multiplication();
                    Node finalLeft = left;
                    left = env -> num(finalLeft.eval(env)) - num(right.eval(env));
                } else {
                    return left;
                }
            }
        }

        private Node multiplication() {
            Node left = unary();
            while (true) {
                if (take("*")) {
                    Node right = unary();
                    Node finalLeft = left;
                    left = env -> num(finalLeft.eval(env)) * num(right.eval(env));
                } else if (take("/")) {
                    Node right = unary();
                    Node finalLeft = left;
                    left = env -> {
                        double divisor = num(right.eval(env));
                        return divisor == 0.0D ? 0.0D : num(finalLeft.eval(env)) / divisor;
                    };
                } else if (take("%")) {
                    Node right = unary();
                    Node finalLeft = left;
                    left = env -> {
                        double divisor = num(right.eval(env));
                        return divisor == 0.0D ? 0.0D : num(finalLeft.eval(env)) % divisor;
                    };
                } else {
                    return left;
                }
            }
        }

        private Node unary() {
            if (take("-")) {
                Node inner = unary();
                return env -> -num(inner.eval(env));
            }
            if (take("+")) {
                return unary();
            }
            if (take("!")) {
                Node inner = unary();
                return env -> truth(inner.eval(env)) ? 0.0D : 1.0D;
            }
            return atom();
        }

        private Node atom() {
            Token token = next();
            if (token.kind().equals("num")) {
                double value = Double.parseDouble(token.text());
                return env -> value;
            }
            if (token.text().equals("(")) {
                Node inner = ternary();
                if (!take(")")) {
                    throw new IllegalArgumentException("缺右括号");
                }
                return inner;
            }
            if (token.kind().equals("str")) {
                // 字符串在数值上下文里当 0（我们只把它交给 call()，比如物品名过滤）
                return env -> 0.0D;
            }
            if (token.kind().equals("id")) {
                if (take("(")) {
                    java.util.List<Node> args = new java.util.ArrayList<>();
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
                return variable(token.text());
            }
            throw new IllegalArgumentException("不认识的值 " + token.text());
        }
    }

    private static Node variable(String name) {
        if (name.startsWith("q.") || name.startsWith("query.") || name.startsWith("ysm.")) {
            return env -> env.queries.getOrDefault(name, 0.0D);
        }
        if (name.startsWith("v.") || name.startsWith("variable.")) {
            return env -> env.variables.containsKey(name) ? env.variables.get(name) : UNDEFINED;
        }
        if (name.startsWith("t.") || name.startsWith("temp.")) {
            return env -> env.temp.containsKey(name) ? env.temp.get(name) : UNDEFINED;
        }
        // 裸名字 / math.* 之类：当作未定义
        return env -> UNDEFINED;
    }

    private static Node call(String name, java.util.List<Node> args) {
        String function = name;
        int dot = function.lastIndexOf(':');
        if (dot >= 0) {
            function = function.substring(dot + 1);
        }
        final String resolved = function;
        return switch (resolved) {
            case "math.sin" -> env -> Math.sin(Math.toRadians(arg(args, 0, env)));
            case "math.cos" -> env -> Math.cos(Math.toRadians(arg(args, 0, env)));
            case "math.tan" -> env -> Math.tan(Math.toRadians(arg(args, 0, env)));
            case "math.asin" -> env -> Math.toDegrees(Math.asin(clampUnit(arg(args, 0, env))));
            case "math.acos" -> env -> Math.toDegrees(Math.acos(clampUnit(arg(args, 0, env))));
            case "math.atan" -> env -> Math.toDegrees(Math.atan(arg(args, 0, env)));
            case "math.atan2" -> env -> Math.toDegrees(Math.atan2(arg(args, 0, env), arg(args, 1, env)));
            case "math.abs" -> env -> Math.abs(arg(args, 0, env));
            case "math.floor" -> env -> Math.floor(arg(args, 0, env));
            case "math.ceil" -> env -> Math.ceil(arg(args, 0, env));
            case "math.round" -> env -> (double) Math.round(arg(args, 0, env));
            case "math.trunc" -> env -> (double) (long) arg(args, 0, env);
            case "math.sqrt" -> env -> Math.sqrt(Math.max(0.0D, arg(args, 0, env)));
            case "math.pow" -> env -> Math.pow(arg(args, 0, env), arg(args, 1, env));
            case "math.exp" -> env -> Math.exp(arg(args, 0, env));
            case "math.ln" -> env -> Math.log(Math.max(1.0E-4D, arg(args, 0, env)));
            case "math.mod" -> env -> {
                double divisor = arg(args, 1, env);
                return divisor == 0.0D ? 0.0D : arg(args, 0, env) % divisor;
            };
            case "math.min" -> env -> args.stream().mapToDouble(node -> num(node.eval(env))).min().orElse(0.0D);
            case "math.max" -> env -> args.stream().mapToDouble(node -> num(node.eval(env))).max().orElse(0.0D);
            case "math.clamp" -> env -> Math.min(Math.max(arg(args, 0, env), arg(args, 1, env)), arg(args, 2, env));
            case "math.random", "random" -> env -> 0.0D;
            // 二阶物理 / 一阶平滑：没有历史状态时按"静止"处理（第 ④ 步先这么来）
            case "second_order", "first_order" -> env -> 0.0D;
            default -> env -> 0.0D;
        };
    }

    private static double arg(java.util.List<Node> args, int index, Env env) {
        return index < args.size() ? num(args.get(index).eval(env)) : 0.0D;
    }

    private static double clampUnit(double value) {
        return Math.max(-1.0D, Math.min(1.0D, value));
    }

    static double num(Object value) {
        if (value == null || value == UNDEFINED) {
            return 0.0D;
        }
        if (value instanceof Double number) {
            return number;
        }
        return 0.0D;
    }

    static boolean truth(Object value) {
        return num(value) != 0.0D;
    }

    private Molang() {
    }
}
