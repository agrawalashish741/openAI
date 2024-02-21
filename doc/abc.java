package com.sismics.books.rest.resource;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.sismics.books.core.dao.jpa.BookDao;
import com.sismics.books.core.dao.jpa.TagDao;
import com.sismics.books.core.dao.jpa.UserBookDao;
import com.sismics.books.core.dao.jpa.UserDao;
import com.sismics.books.core.dao.jpa.criteria.UserBookCriteria;
import com.sismics.books.core.dao.jpa.dto.TagDto;
import com.sismics.books.core.dao.jpa.dto.UserBookDto;
import com.sismics.books.core.event.BookImportedEvent;
import com.sismics.books.core.model.context.AppContext;
import com.sismics.books.core.model.jpa.Book;
import com.sismics.books.core.model.jpa.Tag;
import com.sismics.books.core.model.jpa.User;
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

@Path("/book")
public class BookResource extends BaseResource {

    private BookDao bookDao;
    private TagDao tagDao;
    private UserBookDao userBookDao;
    private UserDao userDao;


    public BookResource() {
        bookDao = new BookDao();
        tagDao = new TagDao();
        userBookDao = new UserBookDao();
        userDao = new UserDao();
    }
    
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    public Response addBook(@FormParam("isbn") String isbn) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        ValidationUtil.validateRequired(isbn, "isbn");
        
        Book book = bookDao.getByIsbn(isbn);
        if (book == null) {
            try {
                book = AppContext.getInstance().getBookDataService().searchBook(isbn);
            } catch (Exception e) {
                throw new ClientException("BookNotFound", e.getCause().getMessage(), e);
            }
            
            bookDao.create(book);
        }
        
        UserBook userBook = userBookDao.getByBook(book.getId(), principal.getId());
        if (userBook == null) {
            userBook = new UserBook();
            userBook.setUserId(principal.getId());
            userBook.setBookId(book.getId());
            userBook.setCreateDate(new Date());
            userBookDao.create(userBook);
        } else {
            throw new ClientException("BookAlreadyAdded", "Book already added");
        }
        
        JSONObject response = new JSONObject();
        response.put("id", userBook.getId());
        return Response.ok().entity(response).build();
    }
    
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteBook(@PathParam("id") String userBookId) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        UserBook userBook = userBookDao.getUserBook(userBookId, principal.getId());
        if (userBook == null) {
            throw new ClientException("BookNotFound", "Book not found with id " + userBookId);
        }
        
        userBookDao.delete(userBook.getId());
        
        JSONObject response = new JSONObject();
        response.put("status", "ok");
        return Response.ok().entity(response).build();
    }
    
    @PUT
    @Path("manual")
    @Produces(MediaType.APPLICATION_JSON)
    public Response addManualBook(
            @FormParam("title") String title,
            @FormParam("subtitle") String subtitle,
            @FormParam("author") String author,
            @FormParam("description") String description,
            @FormParam("isbn10") String isbn10,
            @FormParam("isbn13") String isbn13,
            @FormParam("page_count") Long pageCount,
            @FormParam("language") String language,
            @FormParam("publish_date") String publishDateStr,
            @FormParam("tags") List<String> tagList) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
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
        
        Book bookIsbn10 = bookDao.getByIsbn(isbn10);
        Book bookIsbn13 = bookDao.getByIsbn(isbn13);
        if (bookIsbn10 != null || bookIsbn13 != null) {
            throw new ClientException("BookAlreadyAdded", "Book already added");
        }
        
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
        book.setPublishDate(publishDate);
        
        bookDao.create(book);
        
        UserBook userBook = new UserBook();
        userBook.setUserId(principal.getId());
        userBook.setBookId(book.getId());
        userBook.setCreateDate(new Date());
        userBookDao.create(userBook);
        
        if (tagList != null) {
            Set<String> tagSet = new HashSet<>();
            Set<String> tagIdSet = new HashSet<>();
            List<Tag> tagDbList = tagDao.getByUserId(principal.getId());
            for (Tag tagDb : tagDbList) {
                tagIdSet.add(tagDb.getId());
            }
            for (String tagId : tagList) {
                if (!tagIdSet.contains(tagId)) {
                    throw new ClientException("TagNotFound", MessageFormat.format("Tag not found: {0}", tagId));
                }
                tagSet.add(tagId);
            }
            tagDao.updateTagList(userBook.getId(), tagSet);
        }
        
        JSONObject response = new JSONObject();
        response.put("id", userBook.getId());
        return Response.ok().entity(response).build();
    }
    
    @POST
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateBook(
            @PathParam("id") String userBookId,
            @FormParam("title") String title,
            @FormParam("subtitle") String subtitle,
            @FormParam("author") String author,
            @FormParam("description") String description,
            @FormParam("isbn10") String isbn10,
            @FormParam("isbn13") String isbn13,
            @FormParam("page_count") Long pageCount,
            @FormParam("language") String language,
            @FormParam("publish_date") String publishDateStr,
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
        
        UserBook userBook = userBookDao.getUserBook(userBookId, principal.getId());
        if (userBook == null) {
            throw new ClientException("BookNotFound", "Book not found with id " + userBookId);
        }
        
        Book book = bookDao.getById(userBook.getBookId());
        
        if (!Strings.isNullOrEmpty(isbn10) && book.getIsbn10() != null && !book.getIsbn10().equals(isbn10)) {
            Book bookIsbn10 = bookDao.getByIsbn(isbn10);
            if (bookIsbn10 != null) {
                throw new ClientException("BookAlreadyAdded", "Book already added");
            }
        }
        
        if (!Strings.isNullOrEmpty(isbn13) && book.getIsbn13() != null && !book.getIsbn13().equals(isbn13)) {
            Book bookIsbn13 = bookDao.getByIsbn(isbn13);
            if (bookIsbn13 != null) {
                throw new ClientException("BookAlreadyAdded", "Book already added");
            }
        }
        
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
        
        if (tagList != null) {
            Set<String> tagSet = new HashSet<>();
            Set<String> tagIdSet = new HashSet<>();
            List<Tag> tagDbList = tagDao.getByUserId(principal.getId());
            for (Tag tagDb : tagDbList) {
                tagIdSet.add(tagDb.getId());
            }
            for (String tagId : tagList) {
                if (!tagIdSet.contains(tagId)) {
                    throw new ClientException("TagNotFound", MessageFormat.format("Tag not found: {0}", tagId));
                }
                tagSet.add(tagId);
            }
            tagDao.updateTagList(userBookId, tagSet);
        }
        
        JSONObject response = new JSONObject();
        response.put("id", userBookId);
        return Response.ok().entity(response).build();
    }
    
    @GET
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getBook(@PathParam("id") String userBookId) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        UserBook userBook = userBookDao.getUserBook(userBookId, principal.getId());
        if (userBook == null) {
            throw new ClientException("BookNotFound", "Book not found with id " + userBookId);
        }
        
        Book bookDb = bookDao.getById(userBook.getBookId());
        
        JSONObject bookJson = new JSONObject();
        bookJson.put("id", userBook.getId());
        bookJson.put("title", bookDb.getTitle());
        bookJson.put("subtitle", bookDb.getSubtitle());
        bookJson.put("author", bookDb.getAuthor());
        bookJson.put("page_count", bookDb.getPageCount());
        bookJson.put("description", bookDb.getDescription());
        bookJson.put("isbn10", bookDb.getIsbn10());
        bookJson.put("isbn13", bookDb.getIsbn13());
        bookJson.put("language", bookDb.getLanguage());
        if (bookDb.getPublishDate() != null) {
            bookJson.put("publish_date", bookDb.getPublishDate().getTime());
        }
        bookJson.put("create_date", userBook.getCreateDate().getTime());
        if (userBook.getReadDate() != null) {
            bookJson.put("read_date", userBook.getReadDate().getTime());
        }
        
        List<TagDto> tagDtoList = tagDao.getByUserBookId(userBookId);
        List<JSONObject> tags = new ArrayList<>();
        for (TagDto tagDto : tagDtoList) {
            JSONObject tagJson = new JSONObject();
            tagJson.put("id", tagDto.getId());
            tagJson.put("name", tagDto.getName());
            tagJson.put("color", tagDto.getColor());
            tags.add(tagJson);
        }
        bookJson.put("tags", tags);
        
        return Response.ok().entity(bookJson).build();
    }
    
    @GET
    @Path("{id: [a-z0-9\\-]+}/cover")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadCover(@PathParam("id") final String userBookId) throws JSONException {
        UserBook userBook = userBookDao.getUserBook(userBookId);
        
        File file = Paths.get(DirectoryUtil.getBookDirectory().getPath(), userBook.getBookId()).toFile();
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

        return Response.ok(inputStream)
                .header("Content-Type", "image/jpeg")
                .header("Expires", new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z").format(new Date().getTime() + 3600000))
                .build();
    }
    
    @POST
    @Path("{id: [a-z0-9\\-]+}/cover")
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateCover(@PathParam("id") String userBookId, @FormParam("url") String imageUrl) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        UserBook userBook = userBookDao.getUserBook(userBookId, principal.getId());
        if (userBook == null) {
            throw new ClientException("BookNotFound", "Book not found with id " + userBookId);
        }
        
        Book book = bookDao.getById(userBook.getBookId());

        try {
            AppContext.getInstance().getBookDataService().downloadThumbnail(book, imageUrl);
        } catch (Exception e) {
            throw new ClientException("DownloadCoverError", "Error downloading the cover image");
        }
        
        JSONObject response = new JSONObject();
        response.put("status", "ok");
        return Response.ok(response).build();
    }
    
    @GET
    @Path("list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listBooks(
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset,
            @QueryParam("sort_column") Integer sortColumn,
            @QueryParam("asc") Boolean asc,
            @QueryParam("search") String search,
            @QueryParam("read") Boolean read,
            @QueryParam("tag") String tagName) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        JSONObject response = new JSONObject();
        List<JSONObject> books = new ArrayList<>();
        
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
        try {
            userBookDao.findByCriteria(paginatedList, criteria, sortCriteria);
        } catch (Exception e) {
            throw new ServerException("SearchError", "Error searching in books", e);
        }

        for (UserBookDto userBookDto : paginatedList.getResultList()) {
            JSONObject book = new JSONObject();
            book.put("id", userBookDto.getId());
            book.put("title", userBookDto.getTitle());
            book.put("subtitle", userBookDto.getSubtitle());
            book.put("author", userBookDto.getAuthor());
            book.put("language", userBookDto.getLanguage());
            book.put("publish_date", userBookDto.getPublishTimestamp());
            book.put("create_date", userBookDto.getCreateTimestamp());
            book.put("read_date", userBookDto.getReadTimestamp());
            
            List<TagDto> tagDtoList = tagDao.getByUserBookId(userBookDto.getId());
            List<JSONObject> tags = new ArrayList<>();
            for (TagDto tagDto : tagDtoList) {
                JSONObject tag = new JSONObject();
                tag.put("id", tagDto.getId());
                tag.put("name", tagDto.getName());
                tag.put("color", tagDto.getColor());
                tags.add(tag);
            }
            book.put("tags", tags);
            
            books.add(book);
        }
        response.put("total", paginatedList.getResultCount());
        response.put("books", books);
        
        return Response.ok().entity(response).build();
    }
    
    @PUT
    @Consumes("multipart/form-data") 
    @Path("import")
    public Response importBooks(@FormDataParam("file") FormDataBodyPart fileBodyPart) throws JSONException {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        ValidationUtil.validateRequired(fileBodyPart, "file");

        User user = userDao.getById(principal.getId());
        
        InputStream in = fileBodyPart.getValueAs(InputStream.class);
        File importFile =