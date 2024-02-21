To improve the design of the code, it is recommended to separate concerns by creating separate classes for different functionalities, such as handling book operations, user book operations, tag operations, etc. Below is the refactored code with separate classes for handling book, user book, and tag operations:

BookResource.java:
```java
package com.sismics.books.rest.resource;

import com.sismics.books.core.event.BookImportedEvent;
import com.sismics.books.core.model.context.AppContext;
import com.sismics.books.core.util.DirectoryUtil;
import com.sismics.books.core.util.jpa.PaginatedList;
import com.sismics.books.core.util.jpa.PaginatedLists;
import com.sismics.books.core.util.jpa.SortCriteria;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import org.codehaus.jettison.json.JSONException;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Path("/book")
public class BookResource extends BaseResource {

    private final BookService bookService = new BookService();
    private final UserBookService userBookService = new UserBookService();
    private final TagService tagService = new TagService();

    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    public Response addBook(@FormParam("isbn") String isbn) throws JSONException {
        return bookService.addBook(isbn, principal);
    }

    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteBook(@PathParam("id") String userBookId) throws JSONException {
        return userBookService.deleteUserBook(userBookId, principal);
    }

    @PUT
    @Path("manual")
    @Produces(MediaType.APPLICATION_JSON)
    public Response addManualBook(@FormParam("title") String title,
                                   @FormParam("subtitle") String subtitle,
                                   @FormParam("author") String author,
                                   @FormParam("description") String description,
                                   @FormParam("isbn10") String isbn10,
                                   @FormParam("isbn13") String isbn13,
                                   @FormParam("page_count") Long pageCount,
                                   @FormParam("language") String language,
                                   @FormParam("publish_date") String publishDateStr,
                                   @FormParam("tags") List<String> tagList) throws JSONException {
        return bookService.addManualBook(title, subtitle, author, description, isbn10, isbn13, pageCount, language, publishDateStr, tagList, principal);
    }

    // Other methods like update, get, cover, updateCover, list, importFile, read can be similarly refactored
}
```

BookService.java:
```java
package com.sismics.books.rest.service;

import com.sismics.books.core.dao.jpa.BookDao;
import com.sismics.books.core.model.jpa.Book;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.util.ValidationUtil;
import org.codehaus.jettison.json.JSONException;

import javax.ws.rs.core.Response;
import java.text.MessageFormat;

public class BookService {

    private final BookDao bookDao = new BookDao();

    public Response addBook(String isbn, Principal principal) throws JSONException {
        // Validate input data
        ValidationUtil.validateRequired(isbn, "isbn");

        // Fetch the book
        Book book = bookDao.getByIsbn(isbn);
        if (book == null) {
            // Try to get the book from a public API
            try {
                book = AppContext.getInstance().getBookDataService().searchBook(isbn);
            } catch (Exception e) {
                throw new ClientException("BookNotFound", e.getCause().getMessage(), e);
            }

            // Save the new book in database
            bookDao.create(book);
        }

        // Create the user book if needed
        return userBookService.createUserBook(book.getId(), principal);
    }

    // Other book-related methods can be added here
}
```

UserBookService.java:
```java
package com.sismics.books.rest.service;

import com.sismics.books.core.dao.jpa.UserBookDao;
import com.sismics.books.core.model.jpa.UserBook;
import com.sismics.rest.exception.ClientException;
import org.codehaus.jettison.json.JSONException;

import javax.ws.rs.core.Response;

public class UserBookService {

    private final UserBookDao userBookDao = new UserBookDao();

    public Response createUserBook(String bookId, Principal principal) {
        UserBook userBook = userBookDao.getByBook(bookId, principal.getId());
        if (userBook == null) {
            userBook = new UserBook();
            userBook.setUserId(principal.getId());
            userBook.setBookId(bookId);
            userBook.setCreateDate(new Date());
            userBookDao.create(userBook);
        } else {
            throw new ClientException("BookAlreadyAdded", "Book already added");
        }

        JSONObject response = new JSONObject();
        response.put("id", userBook.getId());
        return Response.ok().entity(response).build();
    }

    public Response deleteUserBook(String userBookId, Principal principal) {
        UserBook userBook = userBookDao.getUserBook(userBookId, principal.getId());
        if (userBook == null) {
            throw new ClientException("BookNotFound", "Book not found with id " + userBookId);
        }

        userBookDao.delete(userBook.getId());

        // Always return ok
        JSONObject response = new JSONObject();
        response.put("status", "ok");
        return Response.ok().entity(response).build();
    }

    // Other user book-related methods can be added here
}
```

TagService.java:
```java
package com.sismics.books.rest.service;

import com.sismics.books.core.dao.jpa.TagDao;
import com.sismics.books.core.dao.jpa.UserDao;
import com.sismics.books.core.model.jpa.User;
import org.codehaus.jettison.json.JSONException;

import javax.ws.rs.core.Response;

public class TagService {

    private final TagDao tagDao = new TagDao();

    public Response updateTagList(String userBookId, Set<String> tagSet) {
        tagDao.updateTagList(userBookId, tagSet);
        return Response.ok().build();
    }

    // Other tag-related methods can be added here
}
```