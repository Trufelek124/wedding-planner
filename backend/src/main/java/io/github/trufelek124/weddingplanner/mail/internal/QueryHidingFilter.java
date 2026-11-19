package io.github.trufelek124.weddingplanner.mail.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The return address is called with Google's code and the ticket's state in its query string, and the web framework
 * and Spring Security print the whole request line (query included) once their logging is at DEBUG. This sits in
 * front of both and takes the query string out of what they are shown. The parameters themselves are still read
 * from the container, so the endpoint gets them as always.
 */
class QueryHidingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getQueryString() {
                return null;
            }
        }, response);
    }
}
