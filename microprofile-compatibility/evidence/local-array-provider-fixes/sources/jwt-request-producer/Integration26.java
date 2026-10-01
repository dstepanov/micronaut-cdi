package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration26 {
public static org.eclipse.microprofile.jwt.JsonWebToken token() {
    try {
        var keys=java.security.KeyPairGenerator.getInstance("RSA");keys.initialize(2048);var pair=keys.generateKeyPair();
        String text=io.smallrye.jwt.build.Jwt.issuer("compat").upn("alice").groups(Set.of("users")).sign(pair.getPrivate());
        return new io.smallrye.jwt.auth.principal.DefaultJWTParser(new io.smallrye.jwt.auth.principal.JWTAuthContextInfo(pair.getPublic(),"compat")).parse(text);
    }catch(Exception e){throw new IllegalStateException(e);}
}
@Dependent public static class Producer {
    @Produces @RequestScoped org.eclipse.microprofile.jwt.JsonWebToken current(){return token();}
}
@RequestScoped public static class Reader {
    @Inject org.eclipse.microprofile.jwt.JsonWebToken current;
    public String value(){return current.getName();}
}
@Dependent public static class Consumer {
    @Inject jakarta.enterprise.context.control.RequestContextController controller;
    @Inject Instance<Reader> readers;
    public String run(){controller.activate();try{return readers.get().value();}finally{controller.deactivate();}}
}

}