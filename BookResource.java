One way to improve the design of this code is to extract common functionality into separate methods to reduce code duplication and improve readability. Here is a refactored version of the `BookResource` class:

```java
package com.sismics.books.rest.resource;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Book REST resources.
 *
 * @author bgamard
 */
@Path("/book")
public class BookResource extends BaseResource {

    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    public Response addBook(@FormParam("isbn") String isbn) throws JSONException {
        validateAuthentication();
        validateInput("isbn", isbn);

        Book book = fetchBookByIsbn(isbn);

        UserBook userBook = createUserBook(book.getId());
        saveUserBook(userBook);

        JSONObject response = new JSONObject();
        response.put("id", userBook.getId());
        return Response.ok().entity(response).build();
    }

    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteBook(@PathParam("id") String userBookId) throws JSONException {
        validateAuthentication();

        UserBook userBook = getUserBook(userBookId);
        deleteUserBook(userBook);

        JSONObject response = new JSONObject();
        response.put("status", "ok");
        return Response.ok().entity(response).build();
    }

    @PUT
    @Path("manual")
    @Produces(MediaType.APPLICATION_JSON)
    public Response addManualBook(
            @FormParam("title") String title, @FormParam("subtitle") String subtitle,
            @FormParam("author") String author, @FormParam("description") String description,
            @FormParam("isbn10") String isbn10, @FormParam("isbn13") String isbn13,
            @FormParam("page_count") Long pageCount, @FormParam("language") String language,
            @FormParam("publish_date") String publishDateStr, @FormParam("tags") List<String> tagList) throws JSONException {
        // Implementation
    }

    // Other methods

    private void validateAuthentication() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
    }

    private void validateInput(String param, String value) {
        ValidationUtil.validateRequired(value, param);
    }

    private Book fetchBookByIsbn(String isbn) {
        BookDao bookDao = new BookDao();
        Book book = bookDao.getByIsbn(isbn);
        if (book == null) {
            try {
                book = AppContext.getInstance().getBookDataService().searchBook(isbn);
                bookDao.create(book);
            } catch (Exception e) {
                throw new ClientException("BookNotFound", e.getCause().getMessage(), e);
            }
        }
        return book;
    }

    private UserBook createUserBook(String bookId) {
        UserBookDao userBookDao = new UserBookDao();
        UserBook userBook = userBookDao.getByBook(bookId, principal.getId());
        if (userBook != null) {
            throw new ClientException("BookAlreadyAdded", "Book already added");
        }
        userBook = new UserBook();
        userBook.setUserId(principal.getId());
        userBook.setBookId(bookId);
        userBook.setCreateDate(new Date());
        return userBook;
    }

    private void saveUserBook(UserBook userBook) {
        UserBookDao userBookDao = new UserBookDao();
        userBookDao.create(userBook);
    }

    private UserBook getUserBook(String userBookId) {
        UserBookDao userBookDao = new UserBookDao();
        UserBook userBook = userBookDao.getUserBook(userBookId, principal.getId());
        if (userBook == null) {
            throw new ClientException("BookNotFound", "Book not found with id " + userBookId);
        }
        return userBook;
    }

    private void deleteUserBook(UserBook userBook) {
        UserBookDao userBookDao = new UserBookDao();
        userBookDao.delete(userBook.getId());
    }
}
```

This refactored version breaks down the functionality into smaller, reusable methods for creating, fetching, validating, and deleting books and user books. This approach makes the code more modular, easier to maintain, and reduces code duplication.