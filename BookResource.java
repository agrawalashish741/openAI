package com.sismics.books.rest.resource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.sismics.books.core.dao.jpa.BookDao;
import com.sismics.books.core.dao.jpa.TagDao;
import com.sismics.books.core.dao.jpa.UserBookDao;
import com.sismics.books.core.dao.jpa.criteria.UserBookCriteria;
import com.sismics.books.core.dao.jpa.dto.TagDto;
import com.sismics.books.core.dao.jpa.dto.UserBookDto;
import com.sismics.books.core.event.BookImportedEvent;
import com.sismics.books.core.model.context.AppContext;
import com.sismics.books.core.model.jpa.Book;
import com.sismics.books.core.model.jpa.Tag;
import com.sismics.books.core.model.jpa.UserBook;
import com.sismics.books.core.util.DirectoryUtil;
import com.sismics.books.core.util.jpa.PaginatedList;
import com.sismics.books.core.util.jpa.PaginatedLists;
import com.sismics.books.core.util.jpa.SortCriteria;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import com.sun.jersey.multipart.FormDataBodyPart;
import com.sun.jersey.multipart.FormDataParam;

/**
 * Book REST resources.
 * 
 * @author bgamard
 */
@Path("/book")
public class BookResource extends BaseResource {
    /**
     * Creates a new book.
     * 
     * @param isbn ISBN Number
     * @return Response
     * @throws JSONException
     */
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    public Response add(@FormParam("isbn") String isbn) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Validate input data
        ValidationUtil.validateRequired(isbn, "isbn");

        BookDao bookDao = new BookDao();
        Book book = bookDao.getByIsbn(isbn);
        if (book == null) {
            book = fetchBookFromAPI(isbn, bookDao);
        }

        UserBookDao userBookDao = new UserBookDao();
        UserBook userBook = userBookDao.getByBook(book.getId(), principal.getId());
        if (userBook == null) {
            createNewUserBook(book, userBookDao);
        } else {
            throw new ClientException("BookAlreadyAdded", "Book already added");
        }

        JSONObject response = new JSONObject();
        response.put("id", userBook.getId());
        return Response.ok().entity(response).build();
    }

    private Book fetchBookFromAPI(String isbn, BookDao bookDao) {
        Book book;
        try {
            book = AppContext.getInstance().getBookDataService().searchBook(isbn);
        } catch (Exception e) {
            throw new ClientException("BookNotFound", e.getCause().getMessage(), e);
        }
        bookDao.create(book);
        return book;
    }

    private void createNewUserBook(Book book, UserBookDao userBookDao) {
        UserBook userBook = new UserBook();
        userBook.setUserId(principal.getId());
        userBook.setBookId(book.getId());
        userBook.setCreateDate(new Date());
        userBookDao.create(userBook);
    }

    /**
     * Deletes a book.
     *
     * @param userBookId User book ID
     * @return Response
     * @throws JSONException
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") String userBookId) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        UserBookDao userBookDao = new UserBookDao();
        UserBook userBook = userBookDao.getUserBook(userBookId, principal.getId());
        if (userBook == null) {
            throw new ClientException("BookNotFound", "Book not found with id " + userBookId);
        }

        userBookDao.delete(userBook.getId());

        JSONObject response = new JSONObject();
        response.put("status", "ok");
        return Response.ok().entity(response).build();
    }

    // Add a book book manually.
    @PUT
    @Path("manual")
    @Produces(MediaType.APPLICATION_JSON)
    public Response addManual(@FormParam("title") String title, @FormParam("subtitle") String subtitle,
                              @FormParam("author") String author, @FormParam("description") String description,
                              @FormParam("isbn10") String isbn10, @FormParam("isbn13") String isbn13,
                              @FormParam("page_count") Long pageCount, @FormParam("language") String language,
                              @FormParam("publish_date") String publishDateStr, @FormParam("tags") List<String> tagList)
            throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        validateManualBookInput(title, subtitle, author, description, isbn10, isbn13, language, publishDateStr, tagList);

        Book book = createManualBook(title, subtitle, author, description, isbn10, isbn13, pageCount, language, publishDateStr);

        UserBookDao userBookDao = new UserBookDao();
        UserBook userBook = createUserBook(book, userBookDao);

        updateTags(tagList, userBook.getId());

        JSONObject response = new JSONObject();
        response.put("id", userBook.getId());
        return Response.ok().entity(response).build();
    }

    private void validateManualBookInput(String title, String subtitle, String author, String description, String isbn10,
                                          String isbn13, String language, String publishDateStr, List<String> tagList) {
        title = ValidationUtil.validateLength(title, "title", 1, 255, false);
        subtitle = ValidationUtil.validateLength(subtitle, "subtitle", 1, 255, true);
        author = ValidationUtil.validateLength(author, "author", 1, 255, false);
        description = ValidationUtil.validateLength(description, "description", 1, 4000, true);
        isbn10 = ValidationUtil.validateLength(isbn10, "isbn10", 10, 10, true);
        isbn13 = ValidationUtil.validateLength(isbn13, "isbn13", 13, 13, true);
        language = ValidationUtil.validateLength(language, "language", 2, 2, true);
        Date publishDate = ValidationUtil.validateDate(publishDateStr, "publish_date", false);
        if (Strings.isNullOrEmpty(isbn10) && Strings.isNullOrEmpty(isbn13)) {
            throw new ClientException("ValidationError", "At least one ISBN number is mandatory");
        }
    }

    private Book createManualBook(String title, String subtitle, String author, String description, String isbn10,
                                   String isbn13, Long pageCount, String language, String publishDateStr) {
        Book book = new Book();
        book.setId(UUID.randomUUID().toString());
        book.setTitle(title);
        book.setSubtitle(subtitle);
        book.setAuthor(author);
        book.setDescription(description);
        book.setIsbn10(isbn10);
        book.setIsbn13(isbn13);
        book.setPageCount(pageCount);
        book.setLanguage(language);
        book.setPublishDate(ValidationUtil.validateDate(publishDateStr, "publish_date", false));
        new BookDao().create(book);
        return book;
    }

    private UserBook createUserBook(Book book, UserBookDao userBookDao) {
        UserBook userBook = new UserBook();
        userBook.setUserId(principal.getId());
        userBook.setBookId(book.getId());
        userBook.setCreateDate(new Date());
        userBookDao.create(userBook);
        return userBook;
    }

    private void updateTags(List<String> tagList, String userBookId) {
        TagDao tagDao = new TagDao();
        Set<String> tagSet = new HashSet<>();

        for (String tagId : tagList) {
            Tag tag = tagDao.getById(tagId);
            if (tag == null) {
                throw new ClientException("TagNotFound", MessageFormat.format("Tag not found: {0}", tagId));
            }
            tagSet.add(tagId);
        }

        tagDao.updateTagList(userBookId, tagSet);
    }

    /**
     * Updates the book.
     *
     * @param title Title
     * @param description Description
     * @return Response
     * @throws JSONException
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") String userBookId, @FormParam("title") String title,
                           @FormParam("subtitle") String subtitle, @FormParam("author") String author,
                           @FormParam("description") String description, @FormParam("isbn10") String isbn10,
                           @FormParam("isbn13") String isbn13, @FormParam("page_count") Long pageCount,
                           @FormParam("language") String language, @FormParam("publish_date") String publishDateStr,
                           @FormParam("tags") List<String> tagList) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        title = ValidationUtil.validateLength(title, "title", 1, 255, true);
        subtitle = ValidationUtil.validateLength(subtitle, "subtitle", 1, 255, true);
        author = ValidationUtil.validateLength(author, "author", 1, 255, true);
        description = ValidationUtil.validateLength(description, "description", 1, 4000, true);
        isbn10 = ValidationUtil.validateLength(isbn10, "isbn10", 10, 10, true);
        isbn13 = ValidationUtil.validateLength(isbn13, "isbn13", 13, 13, true);
        language = ValidationUtil.validateLength(language, "language", 2, 2, true);
        Date publishDate = ValidationUtil.validateDate(publishDateStr, "publish_date", true);

        UserBookDao userBookDao = new UserBookDao();
        BookDao bookDao = new BookDao();
        UserBook userBook = userBookDao.getUserBook(userBookId, principal.getId());
        if (userBook == null) {
            throw new ClientException("BookNotFound", "Book not found with id " + userBookId);
        }

        Book book = bookDao.getById(userBook.getBookId());
        validateNewISBNNumbers(isbn10, isbn13, book, bookDao);

        updateBookInformation(title, subtitle, author, description, isbn10, isbn13, pageCount, language, publishDate, book);

        if (tagList != null) {
            updateTags(tagList, userBookId);
        }

        JSONObject response = new JSONObject();
        response.put("id", userBookId);
        return Response.ok().entity(response).build();
    }

    private void validateNewISBNNumbers(String isbn10, String isbn13, Book book, BookDao bookDao) {
        if (Strings.isNullOrEmpty(isbn10) && book.getIsbn10() != null && !book.getIsbn10().equals(isbn10)) {
            Book bookIsbn10 = bookDao.getByIsbn(isbn10);
            if (bookIsbn10 != null) {
                throw new ClientException("BookAlreadyAdded", "Book already added");
            }
        }

        if (Strings.isNullOrEmpty(isbn13) && book.getIsbn13() != null && !book.getIsbn13().equals(isbn13)) {
            Book bookIsbn13 = bookDao.getByIsbn(isbn13);
            if (bookIsbn13 != null) {
                throw new ClientException("BookAlreadyAdded", "Book already added");
            }
        }
    }

    private void updateBookInformation(String title, String subtitle, String author, String description, String isbn10,
                                       String isbn13, Long pageCount, String language, Date publishDate, Book book) {
        if (title != null) {
            book.setTitle(title);
        }
        if (subtitle != null) {
            book.setSubtitle(subtitle);
        }
        if (author != null) {
            book.setAuthor(author);
        }
        if (description != null) {
            book.setDescription(description);
        }
        if (isbn10 != null) {
            book.setIsbn10(isbn10);
        }
        if (isbn13 != null) {
            book.setIsbn13(isbn13);
        }
        if (pageCount != null) {
            book.setPageCount(pageCount);
        }
        if (language != null) {
            book.setLanguage(language);
        }
        if (publishDate != null) {
            book.setPublishDate(publishDate);
        }
    }

    /**
     * Get a book.
     *
     * @param id User book ID
     * @return Response
     * @throws JSONException
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") String userBookId) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        UserBookDao userBookDao = new UserBookDao();
        UserBook userBook = userBookDao.getUserBook(userBookId, principal.getId());
        if (userBook == null) {
            throw new ClientException("BookNotFound", "Book not found with id " + userBookId);
        }

        BookDao bookDao = new BookDao();
        Book bookDb = bookDao.getById(userBook.getBookId());

        JSONObject book = new JSONObject();
        book.put("id", userBook.getId());
        book.put("title", bookDb.getTitle());
        book.put("subtitle", bookDb.getSubtitle());
        book.put("author", bookDb.getAuthor());
        book.put("page_count", bookDb.getPageCount());
        book.put("description", bookDb.getDescription());
        book.put("isbn10", bookDb.getIsbn10());
        book.put("isbn13", bookDb.getIsbn13());
        book.put("language", bookDb.getLanguage());
        if (bookDb.getPublishDate() != null) {
            book.put("publish_date", bookDb.getPublishDate().getTime());
        }
        book.put("create_date", userBook.getCreateDate().getTime());
        if (userBook.getReadDate() != null) {
            book.put("read_date", userBook.getReadDate().getTime());
        }

        addTagsToBookJSON(userBookId, book);

        return Response.ok().entity(book).build();
    }

    private void addTagsToBookJSON(String userBookId, JSONObject book) throws JSONException {
        TagDao tagDao = new TagDao();
        List<TagDto> tagDtoList = tagDao.getByUserBookId(userBookId);
        List<JSONObject> tags = new ArrayList<>();
        for (TagDto tagDto : tagDtoList) {
            JSONObject tag = new JSONObject();
            tag.put("id", tagDto.getId());
            tag.put("name", tagDto.getName());
            tag.put("color", tagDto.getColor());
            tags.add(tag);
        }
        book.put("tags", tags);
    }

    /**
     * Returns a book cover.
     *
     * @param id User book ID
     * @return Response
     * @throws JSONException
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}/cover")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response cover(@PathParam("id") final String userBookId) throws JSONException {
        UserBookDao userBookDao = new UserBookDao();
        UserBook userBook = userBookDao.getUserBook(userBookId);

        File file = Paths.get(DirectoryUtil.getBookDirectory().getPath(), userBook.getBookId()).toFile();
        InputStream inputStream = getFileInputStream(file);

        return Response.ok(inputStream)
                .header("Content-Type", "image/jpeg")
                .header("Expires", new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z").format(new Date().getTime() + 3600000))
                .build();
    }

    private InputStream getFileInputStream(File file) {
        InputStream inputStream = null;
        try {
            if (file.exists()) {
                inputStream = new FileInputStream(file);
            } else {
                inputStream = new FileInputStream(new File(getClass().getResource("/dummy.png").getFile()));
            }
        } catch (FileNotFoundException e) {
            throw new ServerException("FileNotFound", "Cover file not found", e);
        }
        return inputStream;
    }

    // Update a book cover.
    @POST
    @Path("{id: [a-z0-9\\-]+}/cover")
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateCover(@PathParam("id") String userBookId, @FormParam("url") String imageUrl) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        UserBookDao userBookDao = new UserBookDao();
        UserBook userBook = userBookDao.getUserBook(userBookId, principal.getId());
        if (userBook == null) {
            throw new ClientException("BookNotFound", "Book not found with id " + userBookId);
        }

        BookDao bookDao = new BookDao();
        Book book = bookDao.getById(userBook.getBookId());

        downloadCoverImage(imageUrl, book);

        JSONObject response = new JSONObject();
        response.put("status", "ok");
        return Response.ok(response).build();
    }

    private void downloadCoverImage(String imageUrl, Book book) {
        try {
            AppContext.getInstance().getBookDataService().downloadThumbnail(book, imageUrl);
        } catch (Exception e) {
            throw new ClientException("DownloadCoverError", "Error downloading the cover image");
        }
    }

    /**
     * Returns all books.
     */
    @GET
    @Path("list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("limit") Integer limit, @QueryParam("offset") Integer offset,
                         @QueryParam("sort_column") Integer sortColumn, @QueryParam("asc") Boolean asc,
                         @QueryParam("search") String search, @QueryParam("read") Boolean read,
                         @QueryParam("tag") String tagName) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        JSONObject response = new JSONObject();
        List<JSONObject> books = new ArrayList<>();

        UserBookDao userBookDao = new UserBookDao();
        TagDao tagDao = new TagDao();
        PaginatedList<UserBookDto> paginatedList = PaginatedLists.create(limit, offset);
        SortCriteria sortCriteria = new SortCriteria(sortColumn, asc);
        UserBookCriteria criteria = new UserBookCriteria();
        criteria.setSearch(search);
        criteria.setRead(read);
        criteria.setUserId(principal.getId());
        if (!Strings.isNullOrEmpty(tagName)) {
            Tag tag = tagDao.getByName(principal.getId(), tagName);
            if (tag != null) {
                criteria.setTagIdList(Lists.newArrayList(tag.getId()));
            }
        }
        findBooksByCriteria(userBookDao, paginatedList, criteria, sortCriteria, tagDao, books