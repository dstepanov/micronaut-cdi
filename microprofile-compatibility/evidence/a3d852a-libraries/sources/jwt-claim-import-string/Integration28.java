package org.example.mp.generated;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;
import jakarta.inject.*;
import org.eclipse.microprofile.config.*;
import org.eclipse.microprofile.config.inject.*;
import java.util.*;
public class Integration28 {
public static org.eclipse.microprofile.jwt.JsonWebToken token() {
    try {
        var keys=java.security.KeyPairGenerator.getInstance("RSA");keys.initialize(2048);var pair=keys.generateKeyPair();
        String text=io.smallrye.jwt.build.Jwt.issuer("compat").upn("alice").groups(Set.of("users")).sign(pair.getPrivate());
        return new io.smallrye.jwt.auth.principal.DefaultJWTParser(new io.smallrye.jwt.auth.principal.JWTAuthContextInfo(pair.getPublic(),"compat")).parse(text);
    }catch(Exception e){throw new IllegalStateException(e);}
}
@io.micronaut.context.annotation.ClassImport(classes={io.smallrye.jwt.auth.cdi.RawClaimTypeProducer.class,io.smallrye.jwt.auth.cdi.CommonJwtProducer.class,io.smallrye.jwt.auth.cdi.OptionalClaimTypeProducer.class,io.smallrye.jwt.auth.cdi.ClaimValueProducer.class})
public static class Imports {}
@Dependent public static class Producer {
    @Produces @RequestScoped org.eclipse.microprofile.jwt.JsonWebToken current(){return token();}
}
@RequestScoped public static class ClaimsBean {@Inject @org.eclipse.microprofile.jwt.Claim("upn") String value;public String value(){return ""+(value);}}@Dependent public static class Consumer {
    @Inject jakarta.enterprise.context.control.RequestContextController controller;
    @Inject Instance<ClaimsBean> claims;
    public String run(){controller.activate();try{return claims.get().value();}finally{controller.deactivate();}}
}

}