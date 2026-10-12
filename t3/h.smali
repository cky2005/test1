.class public abstract Lt3/h;
.super Ljava/lang/Object;
.source "SourceFile"


# static fields
.field public static volatile a:Lt3/i;


# direct methods
.method static constructor <clinit>()V
    .registers 1

    .line 1
    new-instance v0, Lt3/i;

    .line 3
    invoke-direct {v0}, Lt3/a;-><init>()V

    .line 6
    sput-object v0, Lt3/h;->a:Lt3/i;

    .line 8
    return-void
.end method
