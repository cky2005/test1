.class public abstract Lt3/a;
.super Ljava/lang/Object;
.source "SourceFile"


# instance fields
.field public final a:Ljava/util/concurrent/atomic/AtomicInteger;

.field public b:Z

.field public volatile c:Lcom/fongmi/android/tv/bean/Config;

.field public volatile d:LQ4/G;


# direct methods
.method public constructor <init>()V
    .registers 3

    .line 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 4
    new-instance v0, Ljava/util/concurrent/atomic/AtomicInteger;

    .line 6
    const/4 v1, 0x0

    .line 7
    invoke-direct {v0, v1}, Ljava/util/concurrent/atomic/AtomicInteger;-><init>(I)V

    .line 10
    iput-object v0, p0, Lt3/a;->a:Ljava/util/concurrent/atomic/AtomicInteger;

    .line 12
    return-void
.end method

.method public static b(Ljava/lang/String;)Lcom/google/gson/JsonArray;
    .registers 2

    .line 1
    :try_start_0
    invoke-static {p0}, LU3/f;->a(Ljava/lang/String;)Ljava/lang/String;

    .line 4
    move-result-object p0

    .line 5
    invoke-static {p0}, Lb4/d;->m(Ljava/lang/String;)Ljava/lang/String;

    .line 8
    move-result-object p0

    .line 9
    invoke-static {p0}, Lcom/google/gson/JsonParser;->parseString(Ljava/lang/String;)Lcom/google/gson/JsonElement;

    .line 12
    move-result-object p0

    .line 13
    invoke-virtual {p0}, Lcom/google/gson/JsonElement;->isJsonArray()Z

    .line 16
    move-result v0

    .line 17
    if-eqz v0, :cond_17

    .line 19
    invoke-virtual {p0}, Lcom/google/gson/JsonElement;->getAsJsonArray()Lcom/google/gson/JsonArray;

    .line 22
    move-result-object p0

    .line 23
    goto :goto_1c

    .line 24
    :cond_17
    new-instance p0, Lcom/google/gson/JsonArray;

    .line 26
    invoke-direct {p0}, Lcom/google/gson/JsonArray;-><init>()V
    :try_end_1c
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_1c} :catch_1d

    .line 29
    :goto_1c
    return-object p0

    .line 30
    :catch_1d
    new-instance p0, Lcom/google/gson/JsonArray;

    .line 32
    invoke-direct {p0}, Lcom/google/gson/JsonArray;-><init>()V

    .line 35
    return-object p0
.end method

.method public static c(Lcom/google/gson/JsonObject;Ljava/lang/String;)Lcom/google/gson/JsonArray;
    .registers 4

    .line 1
    invoke-virtual {p0, p1}, Lcom/google/gson/JsonObject;->has(Ljava/lang/String;)Z

    .line 4
    move-result v0

    .line 5
    if-nez v0, :cond_c

    .line 7
    new-instance p0, Lcom/google/gson/JsonArray;

    .line 9
    invoke-direct {p0}, Lcom/google/gson/JsonArray;-><init>()V

    .line 12
    return-object p0

    .line 13
    :cond_c
    invoke-virtual {p0, p1}, Lcom/google/gson/JsonObject;->get(Ljava/lang/String;)Lcom/google/gson/JsonElement;

    .line 16
    move-result-object p0

    .line 17
    invoke-virtual {p0}, Lcom/google/gson/JsonElement;->isJsonObject()Z

    .line 20
    move-result p1

    .line 21
    if-eqz p1, :cond_1c

    .line 23
    new-instance p0, Lcom/google/gson/JsonArray;

    .line 25
    invoke-direct {p0}, Lcom/google/gson/JsonArray;-><init>()V

    .line 28
    return-object p0

    .line 29
    :cond_1c
    invoke-virtual {p0}, Lcom/google/gson/JsonElement;->isJsonPrimitive()Z

    .line 32
    move-result p1

    .line 33
    if-eqz p1, :cond_2a

    .line 35
    invoke-virtual {p0}, Lcom/google/gson/JsonElement;->getAsString()Ljava/lang/String;

    .line 38
    move-result-object p0

    .line 39
    invoke-static {p0}, Lt3/a;->b(Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 42
    move-result-object p0

    .line 43
    :cond_2a
    new-instance p1, Lcom/google/gson/JsonArray;

    .line 45
    invoke-direct {p1}, Lcom/google/gson/JsonArray;-><init>()V

    .line 48
    invoke-virtual {p0}, Lcom/google/gson/JsonElement;->getAsJsonArray()Lcom/google/gson/JsonArray;

    .line 51
    move-result-object p0

    .line 52
    invoke-virtual {p0}, Lcom/google/gson/JsonArray;->iterator()Ljava/util/Iterator;

    .line 55
    move-result-object p0

    .line 56
    :cond_37
    :goto_37
    invoke-interface {p0}, Ljava/util/Iterator;->hasNext()Z

    .line 59
    move-result v0

    .line 60
    if-eqz v0, :cond_5f

    .line 62
    invoke-interface {p0}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    .line 65
    move-result-object v0

    .line 66
    check-cast v0, Lcom/google/gson/JsonElement;

    .line 68
    invoke-virtual {v0}, Lcom/google/gson/JsonElement;->isJsonPrimitive()Z

    .line 71
    move-result v1

    .line 72
    if-eqz v1, :cond_55

    .line 74
    invoke-virtual {v0}, Lcom/google/gson/JsonElement;->getAsString()Ljava/lang/String;

    .line 77
    move-result-object v0

    .line 78
    invoke-static {v0}, Lt3/a;->b(Ljava/lang/String;)Lcom/google/gson/JsonArray;

    .line 81
    move-result-object v0

    .line 82
    invoke-virtual {p1, v0}, Lcom/google/gson/JsonArray;->addAll(Lcom/google/gson/JsonArray;)V

    .line 85
    goto :goto_37

    .line 86
    :cond_55
    invoke-virtual {v0}, Lcom/google/gson/JsonElement;->isJsonObject()Z

    .line 89
    move-result v1

    .line 90
    if-eqz v1, :cond_37

    .line 92
    invoke-virtual {p1, v0}, Lcom/google/gson/JsonArray;->add(Lcom/google/gson/JsonElement;)V

    .line 95
    goto :goto_37

    .line 96
    :cond_5f
    return-object p1
.end method

.method public static j(Ljava/util/ArrayList;)V
    .registers 6

    .line 1
    invoke-static {}, Lb4/d;->e()Lb4/b;

    .line 4
    move-result-object v0

    .line 5
    iget-object v0, v0, Lb4/b;->a:Lj$/util/concurrent/ConcurrentHashMap;

    .line 7
    invoke-static {p0}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 10
    move-result-object p0

    .line 11
    new-instance v1, LB3/a;

    .line 13
    const/4 v2, 0x7

    .line 14
    invoke-direct {v1, v2}, LB3/a;-><init>(I)V

    .line 17
    invoke-interface {p0, v1}, Lj$/util/stream/Stream;->filter(Ljava/util/function/Predicate;)Lj$/util/stream/Stream;

    .line 20
    move-result-object p0

    .line 21
    new-instance v1, LA7/b;

    .line 23
    const/16 v2, 0x10

    .line 25
    invoke-direct {v1, v2}, LA7/b;-><init>(I)V

    .line 28
    invoke-interface {p0, v1}, Lj$/util/stream/Stream;->map(Ljava/util/function/Function;)Lj$/util/stream/Stream;

    .line 31
    move-result-object p0

    .line 32
    new-instance v1, LB3/a;

    .line 34
    const/16 v2, 0x8

    .line 36
    invoke-direct {v1, v2}, LB3/a;-><init>(I)V

    .line 39
    invoke-interface {p0, v1}, Lj$/util/stream/Stream;->filter(Ljava/util/function/Predicate;)Lj$/util/stream/Stream;

    .line 42
    move-result-object p0

    .line 43
    new-instance v1, LA7/b;

    .line 45
    const/16 v2, 0x11

    .line 47
    invoke-direct {v1, v2}, LA7/b;-><init>(I)V

    .line 50
    new-instance v2, LA7/b;

    .line 52
    const/16 v3, 0x12

    .line 54
    invoke-direct {v2, v3}, LA7/b;-><init>(I)V

    .line 57
    new-instance v3, LB7/d;

    .line 59
    const/4 v4, 0x2

    .line 60
    invoke-direct {v3, v4}, LB7/d;-><init>(I)V

    .line 63
    invoke-static {v1, v2, v3}, Lj$/util/stream/Collectors;->toMap(Ljava/util/function/Function;Ljava/util/function/Function;Ljava/util/function/BinaryOperator;)Lj$/util/stream/Collector;

    .line 66
    move-result-object v1

    .line 67
    invoke-interface {p0, v1}, Lj$/util/stream/Stream;->collect(Lj$/util/stream/Collector;)Ljava/lang/Object;

    .line 70
    move-result-object p0

    .line 71
    check-cast p0, Ljava/util/Map;

    .line 73
    invoke-virtual {v0, p0}, Lj$/util/concurrent/ConcurrentHashMap;->putAll(Ljava/util/Map;)V

    .line 76
    return-void
.end method

.method public static k(Ljava/util/List;)V
    .registers 4

    .line 1
    invoke-static {}, Lb4/d;->a()Lb4/a;

    .line 4
    move-result-object v0

    .line 5
    iget-object v0, v0, Lb4/a;->a:Ljava/util/concurrent/CopyOnWriteArrayList;

    .line 7
    invoke-virtual {v0, p0}, Ljava/util/concurrent/CopyOnWriteArrayList;->addAll(Ljava/util/Collection;)Z

    .line 10
    invoke-static {}, Lb4/d;->l()Lb4/e;

    .line 13
    move-result-object v0

    .line 14
    monitor-enter v0

    .line 15
    :try_start_e
    new-instance v1, LE3/b;

    .line 17
    const/16 v2, 0x16

    .line 19
    invoke-direct {v1, v2}, LE3/b;-><init>(I)V

    .line 22
    invoke-static {p0, v1}, Lj$/lang/Iterable$-EL;->forEach(Ljava/lang/Iterable;Ljava/util/function/Consumer;)V

    .line 25
    iget-object v1, v0, Lb4/e;->a:Ljava/util/concurrent/CopyOnWriteArrayList;

    .line 27
    invoke-static {v1}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 30
    move-result-object v1

    .line 31
    invoke-static {p0}, Lj$/util/Collection$-EL;->stream(Ljava/util/Collection;)Lj$/util/stream/Stream;

    .line 34
    move-result-object p0

    .line 35
    invoke-static {v1, p0}, Lj$/util/stream/Stream$-CC;->concat(Lj$/util/stream/Stream;Lj$/util/stream/Stream;)Lj$/util/stream/Stream;

    .line 38
    move-result-object p0

    .line 39
    invoke-interface {p0}, Lj$/util/stream/Stream;->sorted()Lj$/util/stream/Stream;

    .line 42
    move-result-object p0

    .line 43
    invoke-interface {p0}, Lj$/util/stream/Stream;->toList()Ljava/util/List;

    .line 46
    move-result-object p0

    .line 47
    iget-object v1, v0, Lb4/e;->a:Ljava/util/concurrent/CopyOnWriteArrayList;

    .line 49
    invoke-virtual {v1}, Ljava/util/concurrent/CopyOnWriteArrayList;->clear()V

    .line 52
    iget-object v1, v0, Lb4/e;->a:Ljava/util/concurrent/CopyOnWriteArrayList;

    .line 54
    invoke-virtual {v1, p0}, Ljava/util/concurrent/CopyOnWriteArrayList;->addAll(Ljava/util/Collection;)Z
    :try_end_38
    .catchall {:try_start_e .. :try_end_38} :catchall_3a

    .line 57
    monitor-exit v0

    .line 58
    return-void

    .line 59
    :catchall_3a
    move-exception p0

    .line 60
    :try_start_3b
    monitor-exit v0
    :try_end_3c
    .catchall {:try_start_3b .. :try_end_3c} :catchall_3a

    .line 61
    throw p0
.end method


# virtual methods
.method public abstract a()Lcom/fongmi/android/tv/bean/Config;
.end method

.method public final d()Lcom/fongmi/android/tv/bean/Config;
    .registers 2

    .line 1
    iget-object v0, p0, Lt3/a;->c:Lcom/fongmi/android/tv/bean/Config;

    .line 3
    if-nez v0, :cond_9

    .line 5
    invoke-virtual {p0}, Lt3/a;->a()Lcom/fongmi/android/tv/bean/Config;

    .line 8
    move-result-object v0

    .line 9
    goto :goto_b

    .line 10
    :cond_9
    iget-object v0, p0, Lt3/a;->c:Lcom/fongmi/android/tv/bean/Config;

    .line 12
    :goto_b
    return-object v0
.end method

.method public abstract e()Ljava/lang/String;
.end method

.method public final f(LU4/f;)V
    .registers 5

    .line 1
    iget-object v0, p0, Lt3/a;->a:Ljava/util/concurrent/atomic/AtomicInteger;

    .line 3
    invoke-virtual {v0}, Ljava/util/concurrent/atomic/AtomicInteger;->incrementAndGet()I

    .line 6
    move-result v0

    .line 7
    iget-object v1, p0, Lt3/a;->d:LQ4/G;

    .line 9
    if-eqz v1, :cond_18

    .line 11
    iget-object v1, p0, Lt3/a;->d:LQ4/G;

    .line 13
    invoke-interface {v1}, Ljava/util/concurrent/Future;->isDone()Z

    .line 16
    move-result v1

    .line 17
    if-nez v1, :cond_18

    .line 19
    iget-object v1, p0, Lt3/a;->d:LQ4/G;

    .line 21
    const/4 v2, 0x1

    .line 22
    invoke-interface {v1, v2}, Ljava/util/concurrent/Future;->cancel(Z)Z

    .line 25
    :cond_18
    new-instance v1, LA0/m0;

    .line 27
    const/4 v2, 0x4

    .line 28
    invoke-direct {v1, p0, v0, p1, v2}, LA0/m0;-><init>(Ljava/lang/Object;ILjava/lang/Object;I)V

    .line 31
    sget-object v0, LU3/u;->a:LQ4/H;

    .line 33
    check-cast v0, LQ4/I;

    .line 35
    invoke-virtual {v0, v1}, LQ4/I;->a(Ljava/lang/Runnable;)LQ4/G;

    .line 38
    move-result-object v0

    .line 39
    iput-object v0, p0, Lt3/a;->d:LQ4/G;

    .line 41
    invoke-virtual {p1}, LU4/f;->X()V

    .line 44
    return-void
.end method

.method public abstract g(Lcom/fongmi/android/tv/bean/Config;)V
.end method

.method public final h(Ljava/lang/String;)Z
    .registers 3

    .line 1
    iget-boolean v0, p0, Lt3/a;->b:Z

    .line 3
    if-nez v0, :cond_23

    .line 5
    iget-object v0, p0, Lt3/a;->c:Lcom/fongmi/android/tv/bean/Config;

    .line 7
    if-eqz v0, :cond_23

    .line 9
    iget-object v0, p0, Lt3/a;->c:Lcom/fongmi/android/tv/bean/Config;

    .line 11
    invoke-virtual {v0}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 14
    move-result-object v0

    .line 15
    invoke-static {v0}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z

    .line 18
    move-result v0

    .line 19
    if-nez v0, :cond_23

    .line 21
    iget-object v0, p0, Lt3/a;->c:Lcom/fongmi/android/tv/bean/Config;

    .line 23
    invoke-virtual {v0}, Lcom/fongmi/android/tv/bean/Config;->getUrl()Ljava/lang/String;

    .line 26
    move-result-object v0

    .line 27
    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    .line 30
    move-result p1

    .line 31
    if-eqz p1, :cond_21

    .line 33
    goto :goto_23

    .line 34
    :cond_21
    const/4 p1, 0x0

    .line 35
    goto :goto_24

    .line 36
    :cond_23
    :goto_23
    const/4 p1, 0x1

    .line 37
    :goto_24
    return p1
.end method

.method public abstract i()V
.end method
