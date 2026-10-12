.class public final synthetic Lt3/b;
.super Ljava/lang/Object;
.source "SourceFile"

# interfaces
.implements Ljava/util/function/Predicate;


# instance fields
.field public final synthetic a:I

.field public final synthetic b:Lcom/fongmi/android/tv/bean/Config;


# direct methods
.method public synthetic constructor <init>(Lcom/fongmi/android/tv/bean/Config;I)V
    .registers 3

    .line 1
    iput p2, p0, Lt3/b;->a:I

    iput-object p1, p0, Lt3/b;->b:Lcom/fongmi/android/tv/bean/Config;

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method


# virtual methods
.method public final synthetic and(Ljava/util/function/Predicate;)Ljava/util/function/Predicate;
    .registers 3

    .line 1
    iget v0, p0, Lt3/b;->a:I

    invoke-static {p0, p1}, Lj$/util/function/Predicate$-CC;->$default$and(Ljava/util/function/Predicate;Ljava/util/function/Predicate;)Ljava/util/function/Predicate;

    move-result-object p1

    return-object p1
.end method

.method public final synthetic negate()Ljava/util/function/Predicate;
    .registers 2

    .line 1
    iget v0, p0, Lt3/b;->a:I

    packed-switch v0, :pswitch_data_14

    invoke-static {p0}, Lj$/util/function/Predicate$-CC;->$default$negate(Ljava/util/function/Predicate;)Ljava/util/function/Predicate;

    move-result-object v0

    return-object v0

    :pswitch_a  #0x1
    invoke-static {p0}, Lj$/util/function/Predicate$-CC;->$default$negate(Ljava/util/function/Predicate;)Ljava/util/function/Predicate;

    move-result-object v0

    return-object v0

    :pswitch_f  #0x0
    invoke-static {p0}, Lj$/util/function/Predicate$-CC;->$default$negate(Ljava/util/function/Predicate;)Ljava/util/function/Predicate;

    move-result-object v0

    return-object v0

    :pswitch_data_14
    .packed-switch 0x0
        :pswitch_f  #00000000
        :pswitch_a  #00000001
    .end packed-switch
.end method

.method public final synthetic or(Ljava/util/function/Predicate;)Ljava/util/function/Predicate;
    .registers 3

    .line 1
    iget v0, p0, Lt3/b;->a:I

    invoke-static {p0, p1}, Lj$/util/function/Predicate$-CC;->$default$or(Ljava/util/function/Predicate;Ljava/util/function/Predicate;)Ljava/util/function/Predicate;

    move-result-object p1

    return-object p1
.end method

.method public final test(Ljava/lang/Object;)Z
    .registers 3

    .line 1
    iget v0, p0, Lt3/b;->a:I

    .line 3
    packed-switch v0, :pswitch_data_38

    .line 6
    check-cast p1, Lcom/fongmi/android/tv/bean/Parse;

    .line 8
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Parse;->getName()Ljava/lang/String;

    .line 11
    move-result-object p1

    .line 12
    iget-object v0, p0, Lt3/b;->b:Lcom/fongmi/android/tv/bean/Config;

    .line 14
    invoke-virtual {v0}, Lcom/fongmi/android/tv/bean/Config;->getParse()Ljava/lang/String;

    .line 17
    move-result-object v0

    .line 18
    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    .line 21
    move-result p1

    .line 22
    return p1

    .line 23
    :pswitch_16  #0x1
    check-cast p1, Lcom/fongmi/android/tv/bean/Site;

    .line 25
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Site;->getKey()Ljava/lang/String;

    .line 28
    move-result-object p1

    .line 29
    iget-object v0, p0, Lt3/b;->b:Lcom/fongmi/android/tv/bean/Config;

    .line 31
    invoke-virtual {v0}, Lcom/fongmi/android/tv/bean/Config;->getHome()Ljava/lang/String;

    .line 34
    move-result-object v0

    .line 35
    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    .line 38
    move-result p1

    .line 39
    return p1

    .line 40
    :pswitch_27  #0x0
    check-cast p1, Lcom/fongmi/android/tv/bean/Live;

    .line 42
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Live;->getName()Ljava/lang/String;

    .line 45
    move-result-object p1

    .line 46
    iget-object v0, p0, Lt3/b;->b:Lcom/fongmi/android/tv/bean/Config;

    .line 48
    invoke-virtual {v0}, Lcom/fongmi/android/tv/bean/Config;->getHome()Ljava/lang/String;

    .line 51
    move-result-object v0

    .line 52
    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    .line 55
    move-result p1

    .line 56
    return p1

    .line 57
    :pswitch_data_38
    .packed-switch 0x0
        :pswitch_27  #00000000
        :pswitch_16  #00000001
    .end packed-switch
.end method
