package cn.community.parkingservice;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@Profile("probe & (local | container)")
public class InternalProbeController {
    private final String token;
    public InternalProbeController(@Value("${probe.token}") String token) { this.token=token; }
    @GetMapping("/internal/environment-probe")
    public Map<String,String> probe(@RequestHeader(value="X-Probe-Token",required=false) String supplied) {
        if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return Map.of("marker","parking-internal-probe");
    }
}
