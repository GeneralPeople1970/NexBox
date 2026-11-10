package black.android.webkit;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticField;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.webkit.WebViewFactory")
public interface WebViewFactory {
    @BStaticField
    Boolean sWebViewSupported();

    @BStaticMethod
    Object getUpdateService();
}
