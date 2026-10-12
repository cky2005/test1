.class public final synthetic Lt3/c;
.super Ljava/lang/Object;
.source "SourceFile"

# interfaces
.implements Ljava/util/function/Consumer;


# instance fields
.field public final synthetic a:I

.field public final synthetic b:Ljava/util/Map;


# direct methods
.method public synthetic constructor <init>(Ljava/util/Map;I)V
    .registers 3

    .line 1
    iput p2, p0, Lt3/c;->a:I

    iput-object p1, p0, Lt3/c;->b:Ljava/util/Map;

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method


# virtual methods
.method public final accept(Ljava/lang/Object;)V
    .registers 4

    .line 1
    iget v0, p0, Lt3/c;->a:I

    .line 3
    packed-switch v0, :pswitch_data_2a

    .line 6
    check-cast p1, Lcom/fongmi/android/tv/bean/Site;

    .line 8
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Site;->getKey()Ljava/lang/String;

    .line 11
    move-result-object v0

    .line 12
    iget-object v1, p0, Lt3/c;->b:Ljava/util/Map;

    .line 14
    invoke-interface {v1, v0}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;

    .line 17
    move-result-object v0

    .line 18
    check-cast v0, Lcom/fongmi/android/tv/bean/Site;

    .line 20
    invoke-virtual {p1, v0}, Lcom/fongmi/android/tv/bean/Site;->sync(Lcom/fongmi/android/tv/bean/Site;)Lcom/fongmi/android/tv/bean/Site;

    .line 23
    return-void

    .line 24
    :pswitch_17  #0x0
    check-cast p1, Lcom/fongmi/android/tv/bean/Live;

    .line 26
    invoke-virtual {p1}, Lcom/fongmi/android/tv/bean/Live;->getName()Ljava/lang/String;

    .line 29
    move-result-object v0

    .line 30
    iget-object v1, p0, Lt3/c;->b:Ljava/util/Map;

    .line 32
    invoke-interface {v1, v0}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;

    .line 35
    move-result-object v0

    .line 36
    check-cast v0, Lcom/fongmi/android/tv/bean/Live;

    .line 38
    invoke-virtual {p1, v0}, Lcom/fongmi/android/tv/bean/Live;->sync(Lcom/fongmi/android/tv/bean/Live;)Lcom/fongmi/android/tv/bean/Live;

    .line 41
    return-void

    nop

    .line 43
    :pswitch_data_2a
    .packed-switch 0x0
        :pswitch_17  #00000000
    .end packed-switch
.end method

.method public final synthetic andThen(Ljava/util/function/Consumer;)Ljava/util/function/Consumer;
    .registers 3

    .line 1
    iget v0, p0, Lt3/c;->a:I

    invoke-static {p0, p1}, Lj$/util/function/Consumer$-CC;->$default$andThen(Ljava/util/function/Consumer;Ljava/util/function/Consumer;)Ljava/util/function/Consumer;

    move-result-object p1

    return-object p1
.end method
