"""폰에서 없는 기능을 쓰는지 미리 확인 (실제 폰 없이 빌드할 때 안전장치).
1) invokedynamic(람다) 이 남아 있으면 실패: 안드로이드에는 LambdaMetafactory 가 없어서 앱이 켜지자마자 멈춤
2) java.* / android.* 메서드·필드를 android.jar(API 23) 기준으로 찾고, 없는 것은 API 24~29 에서 생긴 것인지 목록과 비교
사용: python3 check_api.py <클래스 폴더> <android.jar>"""
import os
import re
import struct
import sys
import zipfile

# API 24~29 에서 생긴 것 (minSdk 29 라서 써도 됨). 이 밖에 android.jar(23)에 없는 것은 오류로 봄
LATER_CLASSES = re.compile(r"^java/(util/(Base64|function/|stream/|Optional|Spliterator|StringJoiner|PrimitiveIterator|"
                           r"(Double|Int|Long)SummaryStatistics|concurrent/CompletableFuture)|time/|nio/file/|io/UncheckedIOException|"
                           r"lang/(FunctionalInterface|annotation/Repeatable|reflect/(Executable|Parameter)))")
LATER_METHODS = {
    ("java/util/Map", "getOrDefault"), ("java/util/Map", "forEach"), ("java/util/Map", "putIfAbsent"),
    ("java/util/Map", "computeIfAbsent"), ("java/util/Map", "merge"), ("java/util/List", "sort"),
    ("java/util/Collection", "stream"), ("java/util/Set", "stream"), ("java/util/List", "stream"), ("java/util/Collection", "removeIf"), ("java/lang/Iterable", "forEach"),
    ("java/lang/String", "join"), ("java/util/Arrays", "stream"), ("java/lang/Long", "hashCode"),
    ("java/lang/Integer", "hashCode"), ("java/lang/Boolean", "hashCode"), ("java/lang/Math", "floorMod"),
    ("java/lang/Math", "addExact"), ("java/lang/Math", "multiplyExact"), ("java/lang/Math", "toIntExact"),
    ("java/lang/Math", "subtractExact"), ("java/lang/Math", "floorDiv"), ("java/lang/Integer", "toUnsignedLong"),
    ("java/lang/Long", "compareUnsigned"), ("java/lang/Integer", "compareUnsigned"), ("java/lang/Long", "divideUnsigned"),
    ("java/lang/Long", "remainderUnsigned"), ("java/lang/Integer", "divideUnsigned"), ("java/lang/Integer", "remainderUnsigned"),
    ("java/lang/Integer", "toUnsignedString"), ("java/lang/Long", "toUnsignedString"), ("java/lang/Long", "parseUnsignedLong"),
    ("java/lang/Integer", "parseUnsignedInt"), ("java/util/Objects", "requireNonNull"), ("java/util/Objects", "isNull"),
    ("java/util/Objects", "nonNull"), ("java/lang/Long", "sum"), ("java/lang/Integer", "sum"), ("java/lang/Integer", "max"),
    ("java/lang/Integer", "min"), ("java/lang/Long", "max"), ("java/lang/Long", "min"), ("java/lang/Byte", "toUnsignedInt"),
    ("java/lang/Byte", "toUnsignedLong"), ("java/math/BigInteger", "longValueExact"), ("java/math/BigInteger", "intValueExact"),
    ("java/util/Arrays", "setAll"), ("java/lang/Character", "isAlphabetic"), ("java/lang/ThreadLocal", "withInitial"),
    ("java/util/Collections", "emptyIterator"), ("java/util/Random", "ints"), ("java/util/concurrent/ConcurrentHashMap", "getOrDefault"),
    ("java/util/concurrent/ConcurrentHashMap", "keySet"), ("java/lang/CharSequence", "chars"), ("java/lang/Throwable", "addSuppressed"),
    ("java/security/MessageDigest", "digest"), ("java/nio/ByteBuffer", "flip"), ("java/nio/ByteBuffer", "clear"),
    ("java/nio/ByteBuffer", "compact"), ("java/nio/CharBuffer", "flip"), ("java/nio/CharBuffer", "clear"),
    ("java/util/Comparator", "comparing"), ("java/util/Comparator", "thenComparing"), ("java/util/Comparator", "reversed"),
}


def parse(data):
    """클래스 파일 -> (이름, 부모, 인터페이스들, {(메서드이름, 설명)}, {필드이름}, 참조 목록, invokedynamic 개수)"""
    count = struct.unpack(">H", data[8:10])[0]
    i, idx, cp, indy = 10, 1, {}, 0
    while idx < count:
        tag = data[i]
        if tag == 1:
            n = struct.unpack(">H", data[i + 1:i + 3])[0]
            cp[idx] = ("utf8", data[i + 3:i + 3 + n].decode("utf-8", "replace"))
            i += 3 + n
        elif tag == 7:
            cp[idx] = ("class", struct.unpack(">H", data[i + 1:i + 3])[0])
            i += 3
        elif tag in (8, 16, 19, 20):
            i += 3
        elif tag == 15:
            i += 4
        elif tag in (9, 10, 11):
            a, b = struct.unpack(">HH", data[i + 1:i + 5])
            cp[idx] = ({9: "field", 10: "method", 11: "imethod"}[tag], a, b)
            i += 5
        elif tag == 12:
            a, b = struct.unpack(">HH", data[i + 1:i + 5])
            cp[idx] = ("nat", a, b)
            i += 5
        elif tag in (3, 4, 17, 18):
            if tag == 18:
                indy += 1
            i += 5
        elif tag in (5, 6):
            i += 9
            idx += 1
        idx += 1
    u = lambda k: cp[k][1]
    cname = lambda k: u(cp[k][1])
    access, this, sup, nif = struct.unpack(">HHHH", data[i:i + 8])
    i += 8
    ifaces = [cname(struct.unpack(">H", data[i + 2 * j:i + 2 * j + 2])[0]) for j in range(nif)]
    i += 2 * nif
    fields, methods = set(), set()
    for kind in ("f", "m"):
        n = struct.unpack(">H", data[i:i + 2])[0]
        i += 2
        for _ in range(n):
            _, ni, di, ac = struct.unpack(">HHHH", data[i:i + 8])
            i += 8
            for _ in range(ac):
                ln = struct.unpack(">I", data[i + 2:i + 6])[0]
                i += 6 + ln
            (fields if kind == "f" else methods).add(u(ni) if kind == "f" else (u(ni), u(di)))
    refs = []
    for v in cp.values():
        if v[0] in ("field", "method", "imethod"):
            nat = cp[v[2]]
            refs.append((v[0], cname(v[1]), u(nat[1]), u(nat[2])))
    return cname(this), (cname(sup) if sup else None), ifaces, methods, fields, refs, indy


def main(classes_dir, android_jar):
    plat = {}
    for n in zipfile.ZipFile(android_jar).namelist():
        if n.endswith(".class"):
            plat[n[:-6]] = n
    pz = zipfile.ZipFile(android_jar)
    cache = {}

    def info(c):
        if c not in cache:
            cache[c] = parse(pz.read(plat[c])) if c in plat else None
        return cache[c]

    def has(c, kind, name, desc):
        seen, todo = set(), [c]
        while todo:
            k = todo.pop()
            if k in seen or k is None:
                continue
            seen.add(k)
            inf = info(k)
            if inf is None:
                continue
            if (kind == "field" and name in inf[4]) or (kind != "field" and (name, desc) in inf[3]):
                return True
            todo.append(inf[1])
            todo.extend(inf[2])
        return False

    ours, problems, indy_total = {}, [], 0
    for root, _, files in os.walk(classes_dir):
        for f in files:
            if f.endswith(".class"):
                p = os.path.join(root, f)
                inf = parse(open(p, "rb").read())
                ours[inf[0]] = inf
    for name, inf in sorted(ours.items()):
        if inf[6]:
            indy_total += inf[6]
            problems.append(f"invokedynamic {inf[6]}개: {name}")
        for kind, cls, mname, desc in inf[5]:
            cls = cls.lstrip("[")
            if cls.startswith("L"):
                cls = cls[1:-1]
            if not (cls.startswith("java/") or cls.startswith("javax/") or cls.startswith("android/") or cls.startswith("org/json/")):
                continue
            if cls in ours:
                continue
            if cls not in plat:
                if not LATER_CLASSES.match(cls):
                    problems.append(f"없는 클래스 {cls} <- {name}")
                continue
            if not has(cls, kind, mname, desc) and (cls, mname) not in LATER_METHODS:
                problems.append(f"없는 {'필드' if kind == 'field' else '메서드'} {cls}.{mname}{desc if kind != 'field' else ''} <- {name}")
    # 쓰지 않는 선택 기능(윈도우 에이전트, 외부 로거, 커버로스 등)은 클래스째로 안 불러서 괜찮음
    optional = re.compile(r"<- com/jcraft/jsch/(PageantConnector|Log4j2Logger|Slf4jLogger|JUnixSocketFactory|jgss/|WindowsSSHAgentConnector|"
                          r"UnixDomainSocketFactory|SSHAgentConnector|JavaLogger|AgentProxy)")
    real = [p for p in problems if not optional.search(p)]
    for p in sorted(set(real)):
        print(p)
    print(f"클래스 {len(ours)}개, invokedynamic {indy_total}개, 문제 {len(set(real))}개")
    return 1 if real else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
