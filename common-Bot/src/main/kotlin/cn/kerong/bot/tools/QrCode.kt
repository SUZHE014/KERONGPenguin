package cn.kerong.bot.tools

/**
 * 自包含二维码（QR Code Model 2）编码器 —— 1.7.4 新增，用于服务器控制台
 * 打印 ASCII 二维码（手机 QQ 扫码打开官网教程，引导配置机器人凭据）。
 *
 * 实现范围（够用即可，刻意不追求全版本全模式）：
 * - 编码模式：仅字节模式（Byte Mode，UTF-8），覆盖 URL / 短文本场景；
 * - 纠错级别：M（约 15% 纠错，控制台小尺寸二维码的稳妥默认）；
 * - 版本：1-10（M 级字节容量 14/26/42/62/84/106/122/152/180/213 字节，
 *   官网教程 URL 40 字节落在版本 3）；更高版本直接拒绝（当前无需求）。
 *
 * 算法依据 ISO/IEC 18004（与 Python qrcode 库逐函数对齐移植，行为一致，
 * 门禁与冒烟以该库生成矩阵 + OpenCV 解码双重验证）：
 * - RS 纠错：GF(256)（本原多项式 0x11D），生成多项式连乘 + 多项式取模；
 * - 掩码：8 个标准掩码全部尝试，按四条惩罚规则选最优（与 qrcode 库同式，
 *   惩罚评估在测试矩阵上进行——格式信息区全按浅色计算，与参考实现一致）；
 * - 格式信息：BCH(15,5)（生成元 0x537）异或掩码 0x5412；
 * - 版本信息（版本 ≥7）：BCH(18,6)（生成元 0x1F25）。
 *
 * 控制台渲染：Unicode 半块字符（▀ ▄ █ 空格）两行并一行，二维码近似正方形；
 * 四周静区（quiet zone）默认 4 模块（规范推荐值；控制台紧凑可降 2）。
 * 1.7.5.5 起另备两级非 UTF-8 控制台回退渲染（GBK 中文 Windows 控制台的
 * 半块字符会被替换为 ?）：全角方块（[encodeCjk]，■ + 全角空格）与纯 ASCII
 * （[encodePlain]，## + 空格对），按控制台可编码性由调用方选择。
 */
object QrCode {

    /** 纠错级别 M 的格式信息位（格式信息数据的高 2 位，L=01/M=00/Q=11/H=10）。 */
    private const val EC_LEVEL_M_BITS = 0b00

    /** BCH(15,5) 生成多项式（格式信息）。 */
    private const val G15 = 0b10100110111

    /** 格式信息掩码（防全 0 等病态序列）。 */
    private const val G15_MASK = 0b101010000010010

    /** BCH(18,6) 生成多项式（版本信息，版本 ≥7）。 */
    private const val G18 = 0b1111100100101

    /** GF(256) 本原多项式 0x11D 的指数 / 对数表（EXP 周期 255）。 */
    private val EXP = IntArray(256)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
    }

    // ---------- 对外入口 ----------

    /** 编码结果矩阵（true = 深色模块）。 */
    class Matrix(val size: Int, val modules: Array<BooleanArray>)

    /**
     * 编码文本为二维码矩阵（字节模式 / 纠错 M / 自动选版本 1-10）。
     * @throws IllegalArgumentException 文本为空或超出版本 10-M 的字节容量
     */
    @JvmStatic
    fun encode(text: String): Matrix {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "二维码内容不能为空" }
        val version = (1..10).firstOrNull { v ->
            dataCodewordBits(v) - 4 - countIndicatorBits(v) >= bytes.size * 8
        } ?: throw IllegalArgumentException(
            "二维码内容过长（${bytes.size} 字节，超过版本 10-M 上限 213 字节）"
        )
        val data = createCodewords(bytes, version)
        return buildMatrix(data, version)
    }

    /**
     * 编码并渲染为控制台 ASCII 二维码（半块字符，两行并一行）。
     * @param quietZone 四周静区模块数（规范推荐 4；控制台紧凑场景可降 2）
     * @throws IllegalArgumentException 同 [encode]
     */
    @JvmStatic
    fun encodeAscii(text: String, quietZone: Int = 4): String {
        val matrix = encode(text)
        val n = matrix.size
        val q = quietZone.coerceAtLeast(0)
        val total = n + q * 2
        val sb = StringBuilder()
        // 行对（上排 + 下排合并为半块字符行）；奇数行补空白下排
        for (topRow in -q until total - q step 2) {
            val bottomRow = topRow + 1
            for (col in -q until n + q) {
                val up = moduleAt(matrix, topRow, col)
                val down = moduleAt(matrix, bottomRow, col)
                sb.append(when {
                    up && down -> '█'
                    up -> '▀'
                    down -> '▄'
                    else -> ' '
                })
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /**
     * 编码并渲染为全角方块二维码（1.7.5.5 新增，GBK 等非 UTF-8 控制台回退）。
     *
     * 半块字符（▀ ▄ █）不在 GBK 字符集——中文 Windows 控制台（默认编码 GBK /
     * cp936）上这些字符会被替换成 ?，二维码不可扫。本渲染器只使用 GBK 可编码
     * 的字符：深色模块 = ■（U+25A0，GBK 0xA1F5），浅色模块与静区 = 全角空格
     * （U+3000，GBK 0xA1A1）。每个模块一行（不做两行合并），全角字符占两列
     * 控制台宽度，与单元格约 1:2 的高宽比互补，二维码近似正方形。
     *
     * @param quietZone 四周静区模块数
     * @throws IllegalArgumentException 同 [encode]
     */
    @JvmStatic
    fun encodeCjk(text: String, quietZone: Int = 4): String {
        val matrix = encode(text)
        val n = matrix.size
        val q = quietZone.coerceAtLeast(0)
        val sb = StringBuilder()
        for (row in -q until n + q) {
            for (col in -q until n + q) {
                sb.append(if (moduleAt(matrix, row, col)) '■' else '\u3000')
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /**
     * 编码并渲染为纯 ASCII 二维码（1.7.5.5 新增，极端编码环境兜底）。
     *
     * 深色模块 = "##"，浅色模块与静区 = "  "（半角空格对）：每个模块两列一行，
     * 纯 7 位 ASCII，任何终端编码都可表示；宽高比同 [encodeCjk]。
     *
     * @param quietZone 四周静区模块数
     * @throws IllegalArgumentException 同 [encode]
     */
    @JvmStatic
    fun encodePlain(text: String, quietZone: Int = 4): String {
        val matrix = encode(text)
        val n = matrix.size
        val q = quietZone.coerceAtLeast(0)
        val sb = StringBuilder()
        for (row in -q until n + q) {
            for (col in -q until n + q) {
                sb.append(if (moduleAt(matrix, row, col)) "##" else "  ")
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** 越界（含静区）按浅色处理。 */
    private fun moduleAt(matrix: Matrix, row: Int, col: Int): Boolean =
        row in 0 until matrix.size && col in 0 until matrix.size && matrix.modules[row][col]

    /** 冒烟测试用编码详情（不进生产路径）。 */
    class DebugInfo(val version: Int, val codewords: IntArray, val mask: Int, val formatBits: Int)

    /** 冒烟测试用：版本 / 全部码字 / 选中掩码 / 格式信息位（与 [encode] 同路径）。 */
    @JvmStatic
    fun encodeDebug(text: String): DebugInfo {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "二维码内容不能为空" }
        val version = (1..10).firstOrNull { v ->
            dataCodewordBits(v) - 4 - countIndicatorBits(v) >= bytes.size * 8
        } ?: throw IllegalArgumentException("二维码内容过长")
        val data = createCodewords(bytes, version)
        val mask = selectBestMask(data, version)
        return DebugInfo(version, data, mask, bchTypeInfo(EC_LEVEL_M_BITS shl 3 or mask))
    }

    /** 掩码选择（buildMatrix 与 encodeDebug 共用）：全部 8 个掩码按惩罚分选最优。 */
    private fun selectBestMask(data: IntArray, version: Int): Int {
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            val test = blankCanvas(version)
            writeTypeInfo(test, mask, testMode = true)
            if (version >= 7) writeVersionInfo(test, version, testMode = true)
            mapData(test, data, mask)
            val penalty = lostPoint(test)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
            }
        }
        return bestMask
    }

    // ---------- 数据码字 ----------

    /** 版本 v 的 M 级数据码字总位数。 */
    private fun dataCodewordBits(version: Int): Int =
        dataBlocks(version).sumOf { it.second } * 8

    /** 字节模式的字符计数指示位数（版本 1-9 为 8 位，10-40 为 16 位）。 */
    private fun countIndicatorBits(version: Int): Int = if (version < 10) 8 else 16

    /**
     * M 级 RS 块结构（版本 → 块列表，每块 = (总码字数, 数据码字数)）。
     * 与 ISO/IEC 18004 表（Python qrcode 库 RS_BLOCK_TABLE 校验一致）。
     */
    private fun dataBlocks(version: Int): List<Pair<Int, Int>> = when (version) {
        1 -> listOf(26 to 16)
        2 -> listOf(44 to 28)
        3 -> listOf(70 to 44)
        4 -> listOf(50 to 32, 50 to 32)
        5 -> listOf(67 to 43, 67 to 43)
        6 -> listOf((43 to 27), (43 to 27), (43 to 27), (43 to 27))
        7 -> listOf((49 to 31), (49 to 31), (49 to 31), (49 to 31))
        8 -> listOf(60 to 38, 60 to 38, 61 to 39, 61 to 39)
        9 -> listOf(58 to 36, 58 to 36, 58 to 36, 59 to 37, 59 to 37)
        10 -> listOf(69 to 43, 69 to 43, 69 to 43, 69 to 43, 70 to 44)
        else -> throw IllegalArgumentException("不支持的二维码版本 $version")
    }

    /** 字节模式数据 → 比特流 → 终止符 → 字节对齐 → 填充字节 → RS 纠错 + 交织。 */
    private fun createCodewords(bytes: ByteArray, version: Int): IntArray {
        val blocks = dataBlocks(version)
        val dataCount = blocks.sumOf { it.second }
        val bitLimit = dataCount * 8

        // 比特流：模式 0100 + 字符计数 + 数据
        val buffer = IntArray(dataCount)
        var length = 0
        fun putBits(value: Int, count: Int) {
            for (i in 0 until count) {
                val bit = (value ushr (count - i - 1)) and 1
                if (bit == 1) buffer[length / 8] = buffer[length / 8] or (0x80 ushr (length % 8))
                length++
            }
        }
        putBits(0b0100, 4)
        putBits(bytes.size, countIndicatorBits(version))
        for (b in bytes) putBits(b.toInt() and 0xFF, 8)
        check(length <= bitLimit) { "二维码数据超出演算容量" }

        // 终止符（最多 4 个 0）→ 字节对齐 → 交替填充 0xEC / 0x11
        repeat(minOf(bitLimit - length, 4)) { putBits(0, 1) }
        if (length % 8 != 0) putBits(0, 8 - length % 8)
        var padIndex = 0
        while (length < bitLimit) {
            putBits(if (padIndex % 2 == 0) 0xEC else 0x11, 8)
            padIndex++
        }

        // RS 纠错（逐块）+ 交织
        val dcData = ArrayList<IntArray>()
        val ecData = ArrayList<IntArray>()
        var offset = 0
        for ((total, dc) in blocks) {
            val ecCount = total - dc
            val blockData = IntArray(dc) { buffer[offset + it] }
            offset += dc
            dcData.add(blockData)
            ecData.add(rsEncode(blockData, ecCount))
        }
        val maxDc = dcData.maxOf { it.size }
        val maxEc = ecData.maxOf { it.size }
        val out = IntArray(dataCount + ecData.sumOf { it.size })
        var idx = 0
        for (i in 0 until maxDc) {
            for (dc in dcData) if (i < dc.size) out[idx++] = dc[i]
        }
        for (i in 0 until maxEc) {
            for (ec in ecData) if (i < ec.size) out[idx++] = ec[i]
        }
        return out
    }

    // ---------- RS 纠错（GF(256)） ----------

    private fun glog(x: Int): Int {
        require(x != 0) { "glog(0)" }
        return LOG[x]
    }

    private fun gexp(x: Int): Int = EXP[((x % 255) + 255) % 255]

    /** 数据码字 → ecCount 个纠错码字（生成多项式取模）。 */
    private fun rsEncode(data: IntArray, ecCount: Int): IntArray {
        // 生成多项式 ∏(x - α^i)，i = 0..ecCount-1，系数从低到高
        var gen = intArrayOf(1)
        for (i in 0 until ecCount) {
            gen = polyMul(gen, intArrayOf(1, gexp(i)))
        }
        // 数据多项式左移 ecCount 位（乘 x^ecCount）后对 gen 取模
        val rem = data.copyOf(data.size + ecCount)
        for (i in data.indices) {
            val factor = rem[i]
            if (factor == 0) continue
            for (j in 1 until gen.size) {
                rem[i + j] = rem[i + j] xor gmul(gen[j], factor)
            }
        }
        return rem.copyOfRange(data.size, rem.size)
    }

    /** GF(256) 多项式乘法（系数从低到高）。 */
    private fun polyMul(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(a.size + b.size - 1)
        for (i in a.indices) {
            if (a[i] == 0) continue
            for (j in b.indices) {
                out[i + j] = out[i + j] xor gmul(a[i], b[j])
            }
        }
        return out
    }

    /** GF(256) 乘法（对数加速；对数和最大 508，取模 255 防越界）。 */
    private fun gmul(x: Int, y: Int): Int = when {
        x == 0 || y == 0 -> 0
        else -> EXP[(glog(x) + glog(y)) % 255]
    }

    // ---------- 矩阵构建 ----------

    /**
     * 画布：可空模块矩阵（null = 未填充：功能图形与格式 / 版本信息区
     * 先写为非空，数据填充时天然跳过这些保留区——与参考实现一致）。
     */
    private class Canvas(val size: Int) {
        val modules: Array<Array<Boolean?>> = Array(size) { arrayOfNulls(size) }
    }

    private fun blankCanvas(version: Int): Canvas {
        val size = version * 4 + 17
        val canvas = Canvas(size)
        // 三个定位图案（含分隔边）
        positionProbe(canvas, 0, 0)
        positionProbe(canvas, size - 7, 0)
        positionProbe(canvas, 0, size - 7)
        // 校正图形
        val pos = alignmentPositions(version)
        for (r in pos) for (c in pos) {
            if (canvas.modules[r][c] != null) continue
            for (dr in -2..2) for (dc in -2..2) {
                setModule(canvas, r + dr, c + dc,
                    dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0))
            }
        }
        // 时序图形（第 6 行 / 列）
        for (i in 8 until size - 8) {
            if (canvas.modules[i][6] == null) canvas.modules[i][6] = i % 2 == 0
            if (canvas.modules[6][i] == null) canvas.modules[6][i] = i % 2 == 0
        }
        return canvas
    }

    /** 带越界保护的模块写入（定位图案分隔边越出画布时静默忽略）。 */
    private fun setModule(canvas: Canvas, row: Int, col: Int, dark: Boolean) {
        if (row in 0 until canvas.size && col in 0 until canvas.size) {
            canvas.modules[row][col] = dark
        }
    }

    private fun positionProbe(canvas: Canvas, row: Int, col: Int) {
        for (r in -1..7) {
            for (c in -1..7) {
                val dark = (r in 0..6 && (c == 0 || c == 6)) ||
                        (c in 0..6 && (r == 0 || r == 6)) ||
                        (r in 2..4 && c in 2..4)
                setModule(canvas, row + r, col + c, dark)
            }
        }
    }

    /** 版本 → 校正图形中心坐标（ISO 表；版本 1 无校正图形）。 */
    private fun alignmentPositions(version: Int): IntArray = when (version) {
        1 -> intArrayOf()
        2 -> intArrayOf(6, 18)
        3 -> intArrayOf(6, 22)
        4 -> intArrayOf(6, 26)
        5 -> intArrayOf(6, 30)
        6 -> intArrayOf(6, 34)
        7 -> intArrayOf(6, 22, 38)
        8 -> intArrayOf(6, 24, 42)
        9 -> intArrayOf(6, 26, 46)
        10 -> intArrayOf(6, 28, 50)
        else -> throw IllegalArgumentException("不支持的二维码版本 $version")
    }

    /**
     * 完整构建（与参考实现同序）：掩码评估 → 正式矩阵。
     *
     * 掩码评估在测试矩阵上进行（格式 / 版本信息区全部写浅色）；正式矩阵
     * 先写真实格式 / 版本信息（这些保留区变非空，数据填充时自然跳过），
     * 再按锯齿序填数据码字并应用掩码。
     */
    private fun buildMatrix(data: IntArray, version: Int): Matrix {
        val bestMask = selectBestMask(data, version)
        val canvas = blankCanvas(version)
        writeTypeInfo(canvas, bestMask, testMode = false)
        if (version >= 7) writeVersionInfo(canvas, version, testMode = false)
        mapData(canvas, data, bestMask)
        val size = canvas.size
        val out = Array(size) { r -> BooleanArray(size) { c -> canvas.modules[r][c] == true } }
        return Matrix(size, out)
    }

    /**
     * 数据码字按锯齿序放入矩阵，放置的同时应用掩码（与参考实现一致；
     * 掩码只作用于非功能模块——功能模块与格式 / 版本信息区在调用前
     * 已由 [writeTypeInfo] / [writeVersionInfo] 写为非空，天然跳过）。
     */
    private fun mapData(canvas: Canvas, data: IntArray, mask: Int) {
        val size = canvas.size
        var inc = -1
        var row = size - 1
        var bitIndex = 7
        var byteIndex = 0
        // 列迭代器与列值分开（与参考实现的 range 迭代器行为严格一致：
        // 「col <= 6 时 col--」只影响本轮配对值，下一轮迭代器仍按 -2 推进，
        // 否则末尾两对列会错位——丢失列 3 并重复处理列 2，冒烟解码实测踩过）
        var colIter = size - 1
        while (colIter > 0) {
            var col = colIter
            if (col <= 6) col-- // 跳过时序列
            val colPair = col
            while (true) {
                for (c in intArrayOf(colPair, colPair - 1)) {
                    if (canvas.modules[row][c] == null) {
                        var dark = false
                        if (byteIndex < data.size) {
                            dark = ((data[byteIndex] shr bitIndex) and 1) == 1
                        }
                        if (maskApplies(mask, row, c)) dark = !dark
                        canvas.modules[row][c] = dark
                        bitIndex--
                        if (bitIndex == -1) {
                            byteIndex++
                            bitIndex = 7
                        }
                    }
                }
                row += inc
                if (row < 0 || row >= size) {
                    row -= inc
                    inc = -inc
                    break
                }
            }
            colIter -= 2
        }
    }

    /** 掩码条件（8 个标准图案）。 */
    private fun maskApplies(mask: Int, row: Int, col: Int): Boolean = when (mask) {
        0 -> (row + col) % 2 == 0
        1 -> row % 2 == 0
        2 -> col % 3 == 0
        3 -> (row + col) % 3 == 0
        4 -> (row / 2 + col / 3) % 2 == 0
        5 -> (row * col) % 2 + (row * col) % 3 == 0
        6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0
        7 -> ((row + col) % 2 + (row * col) % 3) % 2 == 0
        else -> false
    }

    /** 写格式信息（BCH(15,5)，纠错级 M + 掩码号）与固定暗模块。 */
    private fun writeTypeInfo(canvas: Canvas, mask: Int, testMode: Boolean) {
        val size = canvas.size
        val bits = bchTypeInfo(EC_LEVEL_M_BITS shl 3 or mask)
        // 竖排（左上定位图案旁）
        for (i in 0 until 15) {
            val mod = !testMode && ((bits shr i) and 1) == 1
            if (i < 6) canvas.modules[i][8] = mod
            else if (i < 8) canvas.modules[i + 1][8] = mod
            else canvas.modules[size - 15 + i][8] = mod
        }
        // 横排（上方另一份 + 右下角）
        for (i in 0 until 15) {
            val mod = !testMode && ((bits shr i) and 1) == 1
            if (i < 8) canvas.modules[8][size - i - 1] = mod
            else if (i == 8) canvas.modules[8][7] = mod
            else canvas.modules[8][15 - i - 1] = mod
        }
        // 固定暗模块
        canvas.modules[size - 8][8] = !testMode
    }

    /** 写版本信息（版本 ≥7，BCH(18,6)）。 */
    private fun writeVersionInfo(canvas: Canvas, version: Int, testMode: Boolean) {
        val size = canvas.size
        val bits = bchTypeNumber(version)
        for (i in 0 until 18) {
            val mod = !testMode && ((bits shr i) and 1) == 1
            canvas.modules[i / 3][i % 3 + size - 11] = mod
            canvas.modules[i % 3 + size - 11][i / 3] = mod
        }
    }

    /** BCH(15,5)：数据左移 10 位后对 G15 多项式取模，再并回低 10 位，异或掩码。 */
    private fun bchTypeInfo(data: Int): Int {
        var d = data shl 10
        while (bitLength(d) - bitLength(G15) >= 0) {
            d = d xor (G15 shl (bitLength(d) - bitLength(G15)))
        }
        return ((data shl 10) or d) xor G15_MASK
    }

    /** BCH(18,6)：版本号左移 12 位后对 G18 取模，并回低 12 位。 */
    private fun bchTypeNumber(version: Int): Int {
        var d = version shl 12
        while (bitLength(d) - bitLength(G18) >= 0) {
            d = d xor (G18 shl (bitLength(d) - bitLength(G18)))
        }
        return (version shl 12) or d
    }

    /** 最高有效位位置。 */
    private fun bitLength(value: Int): Int {
        var v = value
        var digit = 0
        while (v != 0) {
            digit++
            v = v ushr 1
        }
        return digit
    }

    // ---------- 惩罚评估（与 qrcode 库逐条一致） ----------

    /**
     * 四条惩罚规则总分（测试矩阵上调用；此时全部模块均已填充）。
     */
    private fun lostPoint(canvas: Canvas): Int {
        val size = canvas.size
        val m = Array(size) { r -> BooleanArray(size) { c -> canvas.modules[r][c] == true } }
        var lost = 0
        // 规则 1：行 / 列同色连续 ≥5，惩罚 = 连长 - 2（按长度计数后统一加权）
        val runCount = IntArray(size + 1)
        for (r in 0 until size) {
            var len = 1
            for (c in 1 until size) {
                if (m[r][c] == m[r][c - 1]) len++ else {
                    if (len >= 5) runCount[len]++
                    len = 1
                }
            }
            if (len >= 5) runCount[len]++
        }
        for (c in 0 until size) {
            var len = 1
            for (r in 1 until size) {
                if (m[r][c] == m[r - 1][c]) len++ else {
                    if (len >= 5) runCount[len]++
                    len = 1
                }
            }
            if (len >= 5) runCount[len]++
        }
        for (l in 5..size) lost += runCount[l] * (l - 2)
        // 规则 2：2×2 同色块，每块 3 分
        for (r in 0 until size - 1) {
            for (c in 0 until size - 1) {
                if (m[r][c] == m[r][c + 1] && m[r][c] == m[r + 1][c] && m[r][c] == m[r + 1][c + 1]) {
                    lost += 3
                }
            }
        }
        // 规则 3：类定位图形（1011101 前或后接 4 浅色），每处 40 分
        //（扫描方向坐标限定 0..size-11，正交方向全范围）
        for (r in 0 until size) {
            for (c in 0 until size - 10) {
                if (finderLike(m, scan = c, fixed = r, horizontal = true)) lost += 40
            }
        }
        for (c in 0 until size) {
            for (r in 0 until size - 10) {
                if (finderLike(m, scan = r, fixed = c, horizontal = false)) lost += 40
            }
        }
        // 规则 4：深色占比偏离 50%，每 5% 记 10 分
        val dark = m.sumOf { row -> row.count { it } }
        val percent = dark.toDouble() / (size * size)
        lost += (kotlin.math.abs(percent * 100 - 50) / 5).toInt() * 10
        return lost
    }

    /**
     * 10111010000 或 00001011101 模式（类定位图形）。
     * [scan] 为扫描方向坐标（模式沿它推进 0..10，越界由调用方循环边界保证），
     * [fixed] 为正交方向坐标；horizontal = 行内扫描，否则列内扫描。
     */
    private fun finderLike(m: Array<BooleanArray>, scan: Int, fixed: Int, horizontal: Boolean): Boolean {
        fun at(s: Int) = if (horizontal) m[fixed][s] else m[s][fixed]
        val b0 = at(scan)
        val b1 = at(scan + 1)
        val b2 = at(scan + 2)
        val b3 = at(scan + 3)
        val b4 = at(scan + 4)
        val b5 = at(scan + 5)
        val b6 = at(scan + 6)
        val b7 = at(scan + 7)
        val b8 = at(scan + 8)
        val b9 = at(scan + 9)
        val b10 = at(scan + 10)
        return (!b1 && b4 && !b5 && b6 && !b9 &&
                ((b0 && b2 && b3 && !b7 && !b8 && !b10) || (!b0 && !b2 && !b3 && b7 && b8 && b10)))
    }
}
